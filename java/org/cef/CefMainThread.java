// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
//
// Orion fork addition. See MODIFICATIONS.md.

package org.cef;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A single, permanent thread that owns the whole global CEF context lifecycle
 * ({@code CefInitialize}, {@code CefShutdown} and, when applicable,
 * {@code CefDoMessageLoopWork}) when running in
 * {@link CefSettings.CefInitializationMode#DEDICATED_CEF_THREAD} mode.
 *
 * <p>The thread is named {@code Orion-JCEF-Main} and is intentionally decoupled
 * from the AWT Event Dispatch Thread so that native initialization never blocks
 * the Swing UI. All operations that depend on the CEF owner thread must be
 * submitted here so that they run on the same, consistent thread.
 *
 * <p>Key properties:
 * <ul>
 *   <li>Single worker thread, non-daemon (CEF shutdown must be able to run).</li>
 *   <li>Re-entrant safe: a task submitted from the owner thread itself is run
 *       inline instead of being queued, avoiding self-deadlock.</li>
 *   <li>Rejects new work once shutdown has been requested.</li>
 *   <li>Preserves the original exception as the cause of the returned future.</li>
 * </ul>
 */
public final class CefMainThread implements AutoCloseable {
    /** The canonical name of the dedicated CEF owner thread. */
    public static final String THREAD_NAME = "Orion-JCEF-Main";

    private enum LifecycleState { RUNNING, SHUTTING_DOWN, TERMINATED }

    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final AtomicReference<LifecycleState> state =
            new AtomicReference<>(LifecycleState.RUNNING);
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final Thread worker;

    public CefMainThread() {
        worker = new Thread(this::runLoop, THREAD_NAME);
        // Non-daemon: the JVM must not exit while CEF is still initialized; an
        // orderly shutdown is responsible for terminating this thread.
        worker.setDaemon(false);
        worker.start();
    }

    private void runLoop() {
        try {
            while (true) {
                Runnable task;
                try {
                    task = queue.take();
                } catch (InterruptedException ie) {
                    // A native CEF call must never be interrupted mid-flight.
                    // Ignore stray interrupts and keep serving the queue; a real
                    // stop is signaled by POISON_PILL, not by interruption.
                    continue;
                }
                if (task == POISON_PILL) {
                    break;
                }
                try {
                    task.run();
                } catch (Throwable t) {
                    // Task bodies below always capture their own exceptions into
                    // the associated future, so reaching here is unexpected.
                    System.err.println("[JCEF] Uncaught error on " + THREAD_NAME);
                    t.printStackTrace();
                }
            }
        } finally {
            state.set(LifecycleState.TERMINATED);
            // Drain any stragglers so their futures fail instead of hanging.
            Runnable pending;
            while ((pending = queue.poll()) != null) {
                if (pending != POISON_PILL) {
                    try {
                        pending.run();
                    } catch (Throwable ignore) {
                    }
                }
            }
            terminated.complete(null);
        }
    }

    private static final Runnable POISON_PILL = () -> {};

    /**
     * @return {@code true} if the calling thread is the dedicated CEF owner
     *         thread ({@code Orion-JCEF-Main}).
     */
    public boolean isCefThread() {
        return Thread.currentThread() == worker;
    }

    /** @return the owner thread instance. */
    public Thread getOwnerThread() {
        return worker;
    }

    /**
     * Submit a value-producing task to the owner thread.
     *
     * <p>If called from the owner thread itself the task is executed inline to
     * avoid self-deadlock. Any exception thrown by the task is delivered as the
     * cause of the returned future.
     */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable runnable = () -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };

        if (isCefThread()) {
            // Re-entrant call: run directly rather than enqueue-and-wait.
            runnable.run();
            return future;
        }

        if (state.get() != LifecycleState.RUNNING) {
            future.completeExceptionally(new RejectedExecutionException(
                    THREAD_NAME + " is not accepting tasks (state=" + state.get() + ")"));
            return future;
        }
        queue.add(runnable);
        return future;
    }

    /**
     * Submit a {@link Runnable} to the owner thread.
     *
     * @return a future completed when the runnable has finished (or failed).
     */
    public CompletableFuture<Void> execute(Runnable task) {
        return submit(() -> {
            task.run();
            return null;
        });
    }

    /**
     * Request an orderly shutdown: stop accepting new work and terminate the
     * thread once the queued tasks have drained.
     *
     * @return a future completed when the owner thread has fully terminated.
     */
    public CompletableFuture<Void> shutdownAsync() {
        if (state.compareAndSet(LifecycleState.RUNNING, LifecycleState.SHUTTING_DOWN)) {
            queue.add(POISON_PILL);
        }
        return terminated;
    }

    /**
     * Blocking, bounded shutdown. Never call from the EDT with a long timeout.
     *
     * @return {@code true} if the thread terminated within the timeout.
     */
    public boolean shutdownAndWait(long timeout, TimeUnit unit) {
        shutdownAsync();
        try {
            terminated.get(timeout, unit);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        shutdownAsync();
    }
}
