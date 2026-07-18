// Copyright (c) 2013 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef;

import org.cef.callback.CefSchemeHandlerFactory;
import org.cef.handler.CefAppHandler;
import org.cef.handler.CefAppHandlerAdapter;

import org.cef.CefSettings.CefInitializationMode;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FilenameFilter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Exposes static methods for managing the global CEF context.
 */
public class CefApp extends CefAppHandlerAdapter {
    public final class CefVersion {
        public final int JCEF_COMMIT_NUMBER;

        public final int CEF_VERSION_MAJOR;
        public final int CEF_VERSION_MINOR;
        public final int CEF_VERSION_PATCH;
        public final int CEF_COMMIT_NUMBER;

        public final int CHROME_VERSION_MAJOR;
        public final int CHROME_VERSION_MINOR;
        public final int CHROME_VERSION_BUILD;
        public final int CHROME_VERSION_PATCH;

        private CefVersion(int jcefCommitNo, int cefMajor, int cefMinor, int cefPatch,
                int cefCommitNo, int chrMajor, int chrMin, int chrBuild, int chrPatch) {
            JCEF_COMMIT_NUMBER = jcefCommitNo;

            CEF_VERSION_MAJOR = cefMajor;
            CEF_VERSION_MINOR = cefMinor;
            CEF_VERSION_PATCH = cefPatch;
            CEF_COMMIT_NUMBER = cefCommitNo;

            CHROME_VERSION_MAJOR = chrMajor;
            CHROME_VERSION_MINOR = chrMin;
            CHROME_VERSION_BUILD = chrBuild;
            CHROME_VERSION_PATCH = chrPatch;
        }

        public String getJcefVersion() {
            return CEF_VERSION_MAJOR + "." + CEF_VERSION_MINOR + "." + CEF_VERSION_PATCH + "."
                    + JCEF_COMMIT_NUMBER;
        }

        public String getCefVersion() {
            return CEF_VERSION_MAJOR + "." + CEF_VERSION_MINOR + "." + CEF_VERSION_PATCH;
        }

        public String getChromeVersion() {
            return CHROME_VERSION_MAJOR + "." + CHROME_VERSION_MINOR + "." + CHROME_VERSION_BUILD
                    + "." + CHROME_VERSION_PATCH;
        }

        @Override
        public String toString() {
            return "JCEF Version = " + getJcefVersion() + "\n"
                    + "CEF Version = " + getCefVersion() + "\n"
                    + "Chromium Version = " + getChromeVersion();
        }
    }

    /**
     * The CefAppState gives you a hint if the CefApp is already usable or not
     * usable any more. See values for details.
     */
    public enum CefAppState {
        /**
         * No CefApp instance was created yet. Call getInstance() to create a new
         * one.
         */
        NONE,

        /**
         * CefApp is new created but not initialized yet. No CefClient and no
         * CefBrowser was created until now.
         */
        NEW,

        /**
         * CefApp is in its initializing process. Please wait until initializing is
         * finished.
         */
        INITIALIZING,

        /**
         * CefApp is up and running. At least one CefClient was created and the
         * message loop is running. You can use all classes and methods of JCEF now.
         */
        INITIALIZED,

        /**
         * CEF initialization has failed (for example due to a second process using
         * the same root_cache_path).
         */
        INITIALIZATION_FAILED,

        /**
         * CefApp is in its shutdown process. All CefClients and CefBrowser
         * instances will be disposed. No new CefClient or CefBrowser is allowed to
         * be created. The message loop will be performed until all CefClients and
         * all CefBrowsers are disposed completely.
         */
        SHUTTING_DOWN,

        /**
         * CefApp is terminated and can't be used any more. You can shutdown the
         * application safely now.
         */
        TERMINATED
    }

    /**
     * According the singleton pattern, this attribute keeps
     * one single object of this class.
     */
    private static CefApp self = null;
    private static CefAppHandler appHandler_ = null;
    private static CefAppState state_ = CefAppState.NONE;
    private Timer workTimer_ = null;
    private HashSet<CefClient> clients_ = new HashSet<CefClient>();
    private CefSettings settings_ = null;

    // --- Orion fork: dedicated CEF owner thread support ---------------------
    // Resolved once at construction and never changed afterwards.
    private final CefInitializationMode initMode_;
    // Non-null only in DEDICATED_CEF_THREAD mode; owns the whole global CEF
    // context lifecycle (pre-init, init, message loop work, shutdown).
    private final CefMainThread cefMainThread_;
    // Idempotent async initialization: concurrent callers share this future.
    private CompletableFuture<CefApp> initFuture_ = null;
    // Guards the one-shot native pre-initialization in dedicated mode.
    private boolean preInitialized_ = false;
    // Caches the one-shot native N_Initialize result so it never runs twice,
    // even if the legacy createClient() path and initializeAsync() are mixed.
    private final Object nativeInitLock_ = new Object();
    private Boolean nativeInitResult_ = null;

    /**
     * To get an instance of this class, use the method
     * getInstance() instead of this CTOR.
     *
     * The CTOR is called by getInstance() as needed and
     * loads all required JCEF libraries.
     *
     * @throws UnsatisfiedLinkError
     */
    private CefApp(String[] args, CefSettings settings) throws UnsatisfiedLinkError {
        super(args);
        if (settings != null) settings_ = settings.clone();

        // Orion fork: decide the CEF owner-thread mode exactly once, before any
        // native pre-initialization. macOS/unsupported platforms fall back to
        // LEGACY_EDT with an explanatory log line.
        initMode_ = resolveInitializationMode(settings_);
        cefMainThread_ =
                (initMode_ == CefInitializationMode.DEDICATED_CEF_THREAD) ? new CefMainThread()
                                                                          : null;
        logInit("Initialization mode: " + initMode_ + " (os=" + System.getProperty("os.name")
                + ", arch=" + System.getProperty("os.arch") + ")");

        if (OS.isWindows()) {
            SystemBootstrap.loadLibrary("jawt");
            SystemBootstrap.loadLibrary("chrome_elf");
            SystemBootstrap.loadLibrary("libcef");

            // Other platforms load this library in CefApp.startup().
            SystemBootstrap.loadLibrary("jcef");
        } else if (OS.isLinux()) {
            SystemBootstrap.loadLibrary("cef");
        }
        if (appHandler_ == null) {
            appHandler_ = this;
        }

        // In DEDICATED_CEF_THREAD mode native pre-initialization is deferred to
        // the owner thread and driven by initializeAsync(), so the constructor
        // (which may run on the EDT) stays cheap and non-blocking.
        if (initMode_ == CefInitializationMode.DEDICATED_CEF_THREAD) return;

        // LEGACY_EDT: unchanged upstream behavior. Execute on the AWT event
        // dispatching thread.
        try {
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    // Perform native pre-initialization.
                    if (!N_PreInitialize())
                        throw new IllegalStateException("Failed to pre-initialize native code");
                }
            };
            if (SwingUtilities.isEventDispatchThread())
                r.run();
            else
                SwingUtilities.invokeAndWait(r);
            preInitialized_ = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Centralized, platform-aware resolution of the CEF initialization mode.
     * The requested mode is honored on Windows and Linux; every other platform
     * (macOS in particular, which requires the process main thread and a Cocoa
     * loop) is forced back to {@link CefInitializationMode#LEGACY_EDT}.
     */
    public static CefInitializationMode resolveInitializationMode(CefSettings settings) {
        CefInitializationMode requested =
                (settings != null && settings.initialization_mode != null)
                ? settings.initialization_mode
                : CefInitializationMode.LEGACY_EDT;

        if (requested == CefInitializationMode.DEDICATED_CEF_THREAD
                && !(OS.isWindows() || OS.isLinux())) {
            System.out.println("[JCEF] DEDICATED_CEF_THREAD is not supported on this platform ("
                    + System.getProperty("os.name") + ").");
            System.out.println("[JCEF] Falling back to LEGACY_EDT.");
            return CefInitializationMode.LEGACY_EDT;
        }
        return requested;
    }

    /** @return the resolved CEF initialization mode for this CefApp instance. */
    public final CefInitializationMode getInitializationMode() {
        return initMode_;
    }

    private static void logInit(String message) {
        System.out.println("[JCEF] " + message);
    }

    /**
     * Assign an AppHandler to CefApp. The AppHandler can be used to evaluate
     * application arguments, to register your own schemes and to hook into the
     * shutdown sequence. See CefAppHandler for more details.
     *
     * This method must be called before CefApp is initialized. CefApp will be
     * initialized automatically if you call createClient() the first time.
     * @param appHandler An instance of CefAppHandler.
     *
     * @throws IllegalStateException in case of CefApp is already initialized
     */
    public static void addAppHandler(CefAppHandler appHandler) throws IllegalStateException {
        if (getState().compareTo(CefAppState.NEW) > 0)
            throw new IllegalStateException("Must be called before CefApp is initialized");
        appHandler_ = appHandler;
    }

    /**
     * Get an instance of this class.
     * @return an instance of this class
     * @throws UnsatisfiedLinkError
     */
    public static synchronized CefApp getInstance() throws UnsatisfiedLinkError {
        return getInstance(null, null);
    }

    public static synchronized CefApp getInstance(String[] args) throws UnsatisfiedLinkError {
        return getInstance(args, null);
    }

    public static synchronized CefApp getInstance(CefSettings settings)
            throws UnsatisfiedLinkError {
        return getInstance(null, settings);
    }

    public static synchronized CefApp getInstance(String[] args, CefSettings settings)
            throws UnsatisfiedLinkError {
        if (settings != null) {
            if (getState() != CefAppState.NONE && getState() != CefAppState.NEW)
                throw new IllegalStateException("Settings can only be passed to CEF"
                        + " before createClient is called the first time.");
        }
        if (self == null) {
            if (getState() == CefAppState.TERMINATED)
                throw new IllegalStateException("CefApp was terminated");
            self = new CefApp(args, settings);
            setState(CefAppState.NEW);
        }
        return self;
    }

    public final void setSettings(CefSettings settings) throws IllegalStateException {
        if (getState() != CefAppState.NONE && getState() != CefAppState.NEW)
            throw new IllegalStateException("Settings can only be passed to CEF"
                    + " before createClient is called the first time.");
        settings_ = settings.clone();
    }

    public final CefVersion getVersion() {
        try {
            return N_GetVersion();
        } catch (UnsatisfiedLinkError ule) {
            ule.printStackTrace();
        }
        return null;
    }

    /**
     * Returns the current state of CefApp.
     * @return current state.
     */
    public final static CefAppState getState() {
        synchronized (state_) {
            return state_;
        }
    }

    private static final void setState(final CefAppState state) {
        synchronized (state_) {
            state_ = state;
        }
        // Execute on the AWT event dispatching thread.
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (appHandler_ != null) appHandler_.stateHasChanged(state);
            }
        });
    }

    /**
     * To shutdown the system, it's important to call the dispose
     * method. Calling this method closes all client instances with
     * and all browser instances each client owns. After that the
     * message loop is terminated and CEF is shutdown.
     */
    public synchronized final void dispose() {
        switch (getState()) {
            case NEW:
                // Nothing to do inspite of invalidating the state
                setState(CefAppState.TERMINATED);
                // Orion fork: never pre-initialized natively, but a dedicated
                // owner thread may already be running; terminate it cleanly.
                terminateOwnerThread();
                break;

            case INITIALIZATION_FAILED:
                // Orion fork: a failed dedicated initialization still leaves the
                // owner thread alive. Do not touch native CEF; just terminate.
                setState(CefAppState.TERMINATED);
                terminateOwnerThread();
                CefApp.self = null;
                break;

            case INITIALIZING:
            case INITIALIZED:
                // (3) Shutdown sequence. Close all clients and continue.
                setState(CefAppState.SHUTTING_DOWN);
                if (clients_.isEmpty()) {
                    shutdown();
                } else {
                    // shutdown() will be called from clientWasDisposed() when the last
                    // client is gone.
                    // Use a copy of the HashSet to avoid iterating during modification.
                    HashSet<CefClient> clients = new HashSet<CefClient>(clients_);
                    for (CefClient c : clients) {
                        c.dispose();
                    }
                }
                break;

            case NONE:
            case SHUTTING_DOWN:
            case TERMINATED:
                // Ignore shutdown, CefApp is already terminated, in shutdown progress
                // or was never created (shouldn't be possible)
                break;
        }
    }

    /**
     * Creates a new client instance and returns it to the caller.
     * One client instance is responsible for one to many browser
     * instances
     * @return a new client instance
     */
    public synchronized CefClient createClient() {
        // Orion fork: in DEDICATED_CEF_THREAD mode createClient() must never
        // silently trigger heavy native initialization. Callers initialize
        // explicitly via initializeAsync()/createClientAsync() first.
        if (initMode_ == CefInitializationMode.DEDICATED_CEF_THREAD) {
            if (getState() != CefAppState.INITIALIZED) {
                throw new IllegalStateException(
                        "createClient() requires the CefApp to be INITIALIZED in "
                        + "DEDICATED_CEF_THREAD mode; call initializeAsync() or "
                        + "createClientAsync() first (current state=" + getState() + ")");
            }
            return newClient();
        }

        // LEGACY_EDT: unchanged upstream behavior (lazy init on the EDT).
        switch (getState()) {
            case NEW:
                setState(CefAppState.INITIALIZING);
                initialize();
                // FALL THRU

            case INITIALIZING:
            case INITIALIZED:
                return newClient();

            default:
                throw new IllegalStateException("Can't crate client in state " + state_);
        }
    }

    private synchronized CefClient newClient() {
        CefClient client = new CefClient();
        clients_.add(client);
        return client;
    }

    /**
     * Start (or reuse) an asynchronous global CEF initialization.
     *
     * <p>Idempotent and thread-safe: concurrent calls return the exact same
     * {@link CompletableFuture}, and native pre-initialization / initialization
     * each run at most once. In {@link CefInitializationMode#DEDICATED_CEF_THREAD}
     * mode all native work runs on the {@code Orion-JCEF-Main} thread, so the
     * EDT stays responsive. In {@link CefInitializationMode#LEGACY_EDT} mode the
     * native work runs on the EDT as upstream does, but the caller still gets a
     * future instead of blocking.
     *
     * <p>Never call {@code join()}/{@code get()} on the returned future from the
     * EDT.
     *
     * @return a future completed with this CefApp once INITIALIZED, or completed
     *         exceptionally with a {@link CefInitializationException} on failure.
     */
    public synchronized CompletableFuture<CefApp> initializeAsync() {
        if (initFuture_ != null) return initFuture_;

        CefAppState s = getState();
        if (s == CefAppState.INITIALIZED) {
            initFuture_ = CompletableFuture.completedFuture(this);
            return initFuture_;
        }
        if (s == CefAppState.INITIALIZATION_FAILED || s.compareTo(CefAppState.SHUTTING_DOWN) >= 0) {
            CompletableFuture<CefApp> failed = new CompletableFuture<>();
            failed.completeExceptionally(new CefInitializationException(
                    "Cannot initialize CefApp in state " + s));
            initFuture_ = failed;
            return initFuture_;
        }

        final CompletableFuture<CefApp> future = new CompletableFuture<>();
        initFuture_ = future;
        if (s == CefAppState.NEW) setState(CefAppState.INITIALIZING);

        Runnable initTask = () -> runFullInitialization(future);
        dispatchToOwner(initTask);
        return future;
    }

    /**
     * Create a client once the CefApp is (or becomes) INITIALIZED, without ever
     * blocking the calling thread. Triggers {@link #initializeAsync()} if needed.
     *
     * @return a future completed with a new {@link CefClient}.
     */
    public CompletableFuture<CefClient> createClientAsync() {
        return initializeAsync().thenApply(app -> newClient());
    }

    /**
     * Runs the full native initialization (pre-init + init) on the current
     * owner thread and settles {@code future}. Always invoked on the CEF owner
     * thread: {@code Orion-JCEF-Main} (dedicated) or the EDT (legacy).
     */
    private void runFullInitialization(CompletableFuture<CefApp> future) {
        try {
            // Pre-initialization: in dedicated mode this is the first native
            // call and must happen on the owner thread; in legacy mode it was
            // already done in the constructor.
            if (!preInitialized_) {
                logInit("N_PreInitialize started on thread " + Thread.currentThread().getName());
                long t0 = System.nanoTime();
                if (!N_PreInitialize()) {
                    setState(CefAppState.INITIALIZATION_FAILED);
                    future.completeExceptionally(CefInitializationException.forOperation(
                            "N_PreInitialize", initMode_, getState()));
                    return;
                }
                preInitialized_ = true;
                logInit("N_PreInitialize completed in " + msSince(t0) + " ms");
            }

            logInit("N_Initialize started on thread " + Thread.currentThread().getName());
            long t1 = System.nanoTime();
            boolean ok = doNativeInitialize();
            if (ok) {
                logInit("N_Initialize completed in " + msSince(t1) + " ms");
                logInit("State changed: INITIALIZING -> INITIALIZED");
                setState(CefAppState.INITIALIZED);
                future.complete(this);
            } else {
                setState(CefAppState.INITIALIZATION_FAILED);
                future.completeExceptionally(CefInitializationException.forOperation(
                        "N_Initialize", initMode_, getState()));
            }
        } catch (Throwable t) {
            setState(CefAppState.INITIALIZATION_FAILED);
            future.completeExceptionally(new CefInitializationException(
                    "JCEF native initialization failed on " + Thread.currentThread().getName(), t));
        }
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * Dispatch a global-context task to the CEF owner thread. In dedicated mode
     * that is {@code Orion-JCEF-Main}; in legacy mode it is the EDT (async, so
     * as not to block the caller).
     */
    private void dispatchToOwner(Runnable task) {
        if (initMode_ == CefInitializationMode.DEDICATED_CEF_THREAD) {
            cefMainThread_.execute(task);
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * Perform one native message-loop iteration on the CEF owner thread. Only
     * used with an external message pump (windowless/OSR). In dedicated mode the
     * Swing Timer still schedules on the EDT, but the native call itself is
     * routed to {@code Orion-JCEF-Main} to keep thread ownership consistent.
     */
    private void invokeDoMessageLoopWork() {
        if (initMode_ == CefInitializationMode.DEDICATED_CEF_THREAD) {
            if (cefMainThread_ != null) cefMainThread_.execute(this::N_DoMessageLoopWork);
        } else {
            N_DoMessageLoopWork();
        }
    }

    /**
     * Register a scheme handler factory for the specified |scheme_name| and
     * optional |domain_name|. An empty |domain_name| value for a standard scheme
     * will cause the factory to match all domain names. The |domain_name| value
     * will be ignored for non-standard schemes. If |scheme_name| is a built-in
     * scheme and no handler is returned by |factory| then the built-in scheme
     * handler factory will be called. If |scheme_name| is a custom scheme then
     * also implement the CefApp::OnRegisterCustomSchemes() method in all
     * processes. This function may be called multiple times to change or remove
     * the factory that matches the specified |scheme_name| and optional
     * |domain_name|. Returns false if an error occurs. This function may be
     * called on any thread in the browser process.
     */
    public boolean registerSchemeHandlerFactory(
            String schemeName, String domainName, CefSchemeHandlerFactory factory) {
        try {
            return N_RegisterSchemeHandlerFactory(schemeName, domainName, factory);
        } catch (Exception err) {
            err.printStackTrace();
        }
        return false;
    }

    /**
     * Clear all registered scheme handler factories. Returns false on error. This
     * function may be called on any thread in the browser process.
     */
    public boolean clearSchemeHandlerFactories() {
        try {
            return N_ClearSchemeHandlerFactories();
        } catch (Exception err) {
            err.printStackTrace();
        }
        return false;
    }

    /**
     * This method is called by a CefClient if it was disposed. This causes
     * CefApp to clean up its list of available client instances. If all clients
     * are disposed, CefApp will be shutdown.
     * @param client the disposed client.
     */
    protected final synchronized void clientWasDisposed(CefClient client) {
        clients_.remove(client);
        if (clients_.isEmpty() && getState().compareTo(CefAppState.SHUTTING_DOWN) >= 0) {
            // Shutdown native system.
            shutdown();
        }
    }

    /**
     * Initialize the context.
     * @return true on success.
     */
    private final void initialize() {
        // LEGACY_EDT path (invoked from createClient). Execute on the AWT event
        // dispatching thread, preserving upstream semantics.
        try {
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    if (doNativeInitialize()) {
                        setState(CefAppState.INITIALIZED);
                    } else {
                        setState(CefAppState.INITIALIZATION_FAILED);
                    }
                }
            };
            if (SwingUtilities.isEventDispatchThread())
                r.run();
            else
                SwingUtilities.invokeAndWait(r);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Build the effective {@link CefSettings} (filling in platform-specific
     * paths) and perform native {@code N_Initialize}. Runs on the current CEF
     * owner thread; does not change the CefApp state (the caller does).
     *
     * @return the native initialization result.
     */
    private boolean doNativeInitialize() {
        // One-shot: never call native N_Initialize twice. Legacy runs on the
        // EDT and dedicated on Orion-JCEF-Main, both single-threaded, so this
        // lock is effectively uncontended.
        synchronized (nativeInitLock_) {
            if (nativeInitResult_ != null) return nativeInitResult_;

            boolean result = doNativeInitializeLocked();
            nativeInitResult_ = result;
            return result;
        }
    }

    private boolean doNativeInitializeLocked() {
        String library_path = getJcefLibPath();
        System.out.println("initialize on " + Thread.currentThread() + " with library path "
                + library_path);

        CefSettings settings = settings_ != null ? settings_ : new CefSettings();

        // Avoid to override user values by testing on NULL
        if (OS.isMacintosh()) {
            if (settings.browser_subprocess_path == null) {
                Path path = Paths.get(library_path,
                        "../Frameworks/jcef Helper.app/Contents/MacOS/jcef Helper");
                settings.browser_subprocess_path = path.normalize().toAbsolutePath().toString();
            }
        } else if (OS.isWindows()) {
            if (settings.browser_subprocess_path == null) {
                Path path = Paths.get(library_path, "jcef_helper.exe");
                settings.browser_subprocess_path = path.normalize().toAbsolutePath().toString();
            }
        } else if (OS.isLinux()) {
            if (settings.browser_subprocess_path == null) {
                Path path = Paths.get(library_path, "jcef_helper");
                settings.browser_subprocess_path = path.normalize().toAbsolutePath().toString();
            }
            if (settings.resources_dir_path == null) {
                Path path = Paths.get(library_path);
                settings.resources_dir_path = path.normalize().toAbsolutePath().toString();
            }
            if (settings.locales_dir_path == null) {
                Path path = Paths.get(library_path, "locales");
                settings.locales_dir_path = path.normalize().toAbsolutePath().toString();
            }
        }

        return N_Initialize(appHandler_, settings);
    }

    /**
     * This method is invoked by the native code (currently on Mac only) in case
     * of a termination event (e.g. someone pressed CMD+Q).
     */
    protected final void handleBeforeTerminate() {
        System.out.println("Cmd+Q termination request.");
        // Execute on the AWT event dispatching thread. Always call asynchronously
        // so the call stack has a chance to unwind.
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                CefAppHandler handler =
                        (CefAppHandler) ((appHandler_ == null) ? this : appHandler_);
                if (!handler.onBeforeTerminate()) dispose();
            }
        });
    }

    /**
     * Shut down the context.
     */
    private final void shutdown() {
        // The shutdown must run on the same owner thread used for initialization.
        // Always call asynchronously so the call stack has a chance to unwind.
        Runnable r = new Runnable() {
            @Override
            public void run() {
                System.out.println("shutdown on " + Thread.currentThread());
                logInit("N_Shutdown started on thread " + Thread.currentThread().getName());
                long t0 = System.nanoTime();

                // Shutdown native CEF.
                N_Shutdown();

                logInit("N_Shutdown completed in " + msSince(t0) + " ms");
                setState(CefAppState.TERMINATED);
                CefApp.self = null;
            }
        };

        if (initMode_ == CefInitializationMode.DEDICATED_CEF_THREAD) {
            // Run N_Shutdown on Orion-JCEF-Main, then request that thread to
            // drain and terminate. The request is enqueued from within the
            // worker's own completion callback, so it must be non-blocking to
            // avoid the worker waiting on itself.
            cefMainThread_.execute(r).whenComplete((v, t) -> terminateOwnerThread());
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /**
     * Request an orderly termination of the dedicated owner thread. Non-blocking
     * and safe to call from any thread (including the owner thread itself and
     * the EDT): it only enqueues a stop signal; the thread drains its queue and
     * terminates on its own. The thread is non-daemon, so a pending termination
     * still completes even during JVM shutdown.
     */
    private void terminateOwnerThread() {
        if (cefMainThread_ == null) return;
        cefMainThread_.shutdownAsync();
    }

    /**
     * Perform a single message loop iteration. Used on all platforms except
     * Windows with windowed rendering.
     */
    public final void doMessageLoopWork(final long delay_ms) {
        // Execute on the AWT event dispatching thread.
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (getState() == CefAppState.TERMINATED) return;

                // The maximum number of milliseconds we're willing to wait between
                // calls to DoMessageLoopWork().
                final long kMaxTimerDelay = 1000 / 30; // 30fps

                if (workTimer_ != null) {
                    workTimer_.stop();
                    workTimer_ = null;
                }

                if (delay_ms <= 0) {
                    // Execute the work immediately (on the CEF owner thread).
                    invokeDoMessageLoopWork();

                    // Schedule more work later.
                    doMessageLoopWork(kMaxTimerDelay);
                } else {
                    long timer_delay_ms = delay_ms;
                    // Never wait longer than the maximum allowed time.
                    if (timer_delay_ms > kMaxTimerDelay) timer_delay_ms = kMaxTimerDelay;

                    workTimer_ = new Timer((int) timer_delay_ms, new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent evt) {
                            // Timer has timed out.
                            workTimer_.stop();
                            workTimer_ = null;

                            invokeDoMessageLoopWork();

                            // Schedule more work later.
                            doMessageLoopWork(kMaxTimerDelay);
                        }
                    });
                    workTimer_.start();
                }
            }
        });
    }

    /**
     * This method must be called at the beginning of the main() method to perform platform-
     * specific startup initialization. On Linux this initializes Xlib multithreading and on
     * macOS this dynamically loads the CEF framework.
     * @param args Command-line arguments massed to main().
     * @return True on successful startup.
     */
    public static final boolean startup(String[] args) {
        if (OS.isLinux() || OS.isMacintosh()) {
            SystemBootstrap.loadLibrary("jcef");
            return N_Startup(OS.isMacintosh() ? getCefFrameworkPath(args) : null);
        }
        return true;
    }

    /**
     * Get the path which contains the jcef library
     * @return The path to the jcef library
     */
    private static final String getJcefLibPath() {
        String library_path = System.getProperty("java.library.path");
        String[] paths = library_path.split(System.getProperty("path.separator"));
        for (String path : paths) {
            File dir = new File(path);
            String[] found = dir.list(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return (name.equalsIgnoreCase("libjcef.dylib")
                            || name.equalsIgnoreCase("libjcef.so")
                            || name.equalsIgnoreCase("jcef.dll"));
                }
            });
            if (found != null && found.length != 0) return path;
        }
        return library_path;
    }

    /**
     * Get the path that contains the CEF Framework on macOS.
     * @return The path to the CEF Framework.
     */
    private static final String getCefFrameworkPath(String[] args) {
        // Check for the path on the command-line.
        String switchPrefix = "--framework-dir-path=";
        for (String arg : args) {
            if (arg.startsWith(switchPrefix)) {
                return new File(arg.substring(switchPrefix.length())).getAbsolutePath();
            }
        }

        // Determine the path relative to the JCEF lib location in the app bundle.
        return new File(getJcefLibPath() + "/../Frameworks/Chromium Embedded Framework.framework")
                .getAbsolutePath();
    }

    private final static native boolean N_Startup(String pathToCefFramework);
    private final native boolean N_PreInitialize();
    private final native boolean N_Initialize(CefAppHandler appHandler, CefSettings settings);
    private final native void N_Shutdown();
    private final native void N_DoMessageLoopWork();
    private final native CefVersion N_GetVersion();
    private final native boolean N_RegisterSchemeHandlerFactory(
            String schemeName, String domainName, CefSchemeHandlerFactory factory);
    private final native boolean N_ClearSchemeHandlerFactories();
}
