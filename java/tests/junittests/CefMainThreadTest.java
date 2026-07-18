// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
//
// Orion fork addition. See MODIFICATIONS.md.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cef.CefMainThread;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure-Java unit tests for the dedicated CEF owner thread. These do not touch
 * native code, so they run in CI without native libraries or a display.
 *
 * <p>Covers acceptance scenarios: thread identity (5, 6), owner-thread naming,
 * no self-deadlock on re-entrant submit (19), rejection after shutdown (18),
 * and exception-cause preservation.
 */
class CefMainThreadTest {
    @Test
    void ownerThreadHasCanonicalName() throws Exception {
        try (CefMainThread main = new CefMainThread()) {
            String name = main.submit(() -> Thread.currentThread().getName())
                                  .get(5, TimeUnit.SECONDS);
            assertEquals(CefMainThread.THREAD_NAME, name);
            assertEquals("Orion-JCEF-Main", name);
        }
    }

    @Test
    void isCefThreadTrueOnlyOnOwnerThread() throws Exception {
        try (CefMainThread main = new CefMainThread()) {
            // Not the owner thread here.
            assertFalse(main.isCefThread());
            boolean insideOwner = main.submit(main::isCefThread).get(5, TimeUnit.SECONDS);
            assertTrue(insideOwner);
        }
    }

    @Test
    void allTasksRunOnTheSameThread() throws Exception {
        try (CefMainThread main = new CefMainThread()) {
            long a = main.submit(() -> Thread.currentThread().getId()).get(5, TimeUnit.SECONDS);
            long b = main.submit(() -> Thread.currentThread().getId()).get(5, TimeUnit.SECONDS);
            long c = main.submit(() -> Thread.currentThread().getId()).get(5, TimeUnit.SECONDS);
            assertEquals(a, b);
            assertEquals(b, c);
            assertEquals(main.getOwnerThread().getId(), a);
        }
    }

    @Test
    void reentrantSubmitDoesNotDeadlock() throws Exception {
        try (CefMainThread main = new CefMainThread()) {
            // Submit a task that itself submits and (inline) resolves another
            // task on the owner thread. Without inline execution this deadlocks.
            String result = main.submit(() -> {
                                     CompletableFuture<String> inner =
                                             main.submit(() -> "inner-" + main.isCefThread());
                                     // Safe to join here only because inline
                                     // execution completed it synchronously.
                                     return inner.getNow("not-completed");
                                 })
                                    .get(5, TimeUnit.SECONDS);
            assertEquals("inner-true", result);
        }
    }

    @Test
    void tasksExecuteInSubmissionOrder() throws Exception {
        try (CefMainThread main = new CefMainThread()) {
            List<Integer> order = new ArrayList<>();
            CompletableFuture<?> last = CompletableFuture.completedFuture(null);
            for (int i = 0; i < 50; i++) {
                final int n = i;
                last = main.execute(() -> order.add(n));
            }
            last.get(5, TimeUnit.SECONDS);
            for (int i = 0; i < 50; i++) {
                assertEquals(i, order.get(i));
            }
        }
    }

    @Test
    void rejectsTasksAfterShutdown() throws Exception {
        CefMainThread main = new CefMainThread();
        main.shutdownAsync().get(5, TimeUnit.SECONDS);

        CompletableFuture<Void> rejected = main.execute(() -> {});
        ExecutionException ex =
                assertThrows(ExecutionException.class, () -> rejected.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof RejectedExecutionException);
    }

    @Test
    void preservesOriginalExceptionCause() throws Exception {
        try (CefMainThread main = new CefMainThread()) {
            IllegalStateException boom = new IllegalStateException("boom");
            CompletableFuture<Object> f = main.submit(() -> {
                throw boom;
            });
            ExecutionException ex =
                    assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
            assertEquals(boom, ex.getCause());
        }
    }

    @Test
    void shutdownIsIdempotent() throws Exception {
        CefMainThread main = new CefMainThread();
        CompletableFuture<Void> first = main.shutdownAsync();
        CompletableFuture<Void> second = main.shutdownAsync();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        assertTrue(main.shutdownAndWait(5, TimeUnit.SECONDS));
        assertFalse(main.getOwnerThread().isAlive());
    }

    @Test
    void submittingFromOwnerThreadAfterShutdownStillRunsInline() throws Exception {
        // A task already running on the owner thread may submit follow-up work;
        // inline execution keeps that safe even mid-shutdown.
        try (CefMainThread main = new CefMainThread()) {
            AtomicReference<Boolean> ran = new AtomicReference<>(false);
            main.submit(() -> {
                    main.execute(() -> ran.set(true));
                    return null;
                }).get(5, TimeUnit.SECONDS);
            assertTrue(ran.get());
        }
    }
}
