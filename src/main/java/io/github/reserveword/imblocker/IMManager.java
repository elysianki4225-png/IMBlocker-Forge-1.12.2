package io.github.reserveword.imblocker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.apache.logging.log4j.Logger;

import io.github.reserveword.imblocker.platform.LinuxIMManager;
import io.github.reserveword.imblocker.platform.MacIMManager;
import io.github.reserveword.imblocker.platform.NoopIMManager;
import io.github.reserveword.imblocker.platform.WindowsIMManager;

/**
 * Cross-platform input method controller.
 *
 * <p>The actual input method manipulation is delegated to a platform backend:
 * <ul>
 *     <li>Windows: inactive until a native IME bridge is implemented.</li>
 *     <li>Linux: {@code fcitx5-remote} / {@code fcitx-remote} / {@code ibus}
 *         command line tools, mirroring how the original IMBlocker works.</li>
 *     <li>macOS: not supported yet, see the README (the original implementation
 *         depends on the InputMethodKit framework which is not reachable without
 *         native code).</li>
 *     <li>Anything else: a no-op backend that logs once and stays out of the way.</li>
 * </ul>
 *
 * <p>All methods here are cheap and non-blocking; native work is dispatched to a
 * background thread so no frame ever waits on the operating system.
 */
public abstract class IMManager {

    protected static final Logger LOG = IMBlocker.LOGGER;

    /** Single worker that serializes all native input method calls. */
    protected static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "imblocker-worker");
            thread.setDaemon(true);
            return thread;
        }
    });

    private static volatile IMManager instance;

    /** Last states pushed to the platform; used to suppress redundant calls. */
    private static volatile Boolean lastIMState;
    private static volatile Boolean lastEnglishState;

    /**
     * Detects the platform backend. Never throws; falls back to a no-op backend
     * on any failure so the game is never endangered by IME integration.
     */
    public static IMManager detect() {
        IMManager existing = instance;
        if (existing != null) {
            return existing;
        }
        synchronized (IMManager.class) {
            if (instance != null) {
                return instance;
            }
            String os = System.getProperty("os.name", "").toLowerCase();
            IMManager detected;
            try {
                if (os.contains("mac") || os.contains("darwin")) {
                    detected = new MacIMManager();
                } else if (os.contains("win")) {
                    detected = new WindowsIMManager();
                } else if (os.contains("linux") || os.contains("bsd")) {
                    detected = new LinuxIMManager();

                } else {
                    LOG.warn("[imblocker] Unsupported operating system '{}'; input method management disabled.", os);
                    detected = new NoopIMManager("Unsupported operating system: " + os);
                }
            } catch (Throwable t) {
                LOG.error("[imblocker] Failed to initialize the platform input method backend; disabling input method management.", t);
                detected = new NoopIMManager("Initialization failure: " + t);
            }
            instance = detected;
            LOG.info("[imblocker] Input method backend: {}", detected.name());
            return detected;
        }
    }

    /** Called from the mod initializer to resolve the backend off the main thread. */
    public static void initAsync() {
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    IMManager manager = detect();
                    manager.initialize();
                } catch (Throwable t) {
                    LOG.error("[imblocker] Background initialization of the input method backend failed.", t);
                }
            }
        });
    }

    /**
     * Sets whether the input method is active for the game window.
     * Calls are deduplicated: the platform is only touched on actual transitions.
     */
    public static void setState(final boolean enabled) {
        Boolean last = lastIMState;
        if (last != null && last.booleanValue() == enabled) {
            return;
        }
        lastIMState = Boolean.valueOf(enabled);
        final IMManager manager = detect();
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    manager.setIMEnabled(enabled);
                } catch (Throwable t) {
                    LOG.error("[imblocker] Failed to set input method state to " + enabled, t);
                }
            }
        });
    }

    /**
     * Sets the English (half-width / alphanumeric) conversion mode preference.
     * Only meaningful on Windows; a no-op elsewhere (same as the original mod).
     */
    public static void setEnglishState(final boolean english) {
        Boolean last = lastEnglishState;
        if (last != null && last.booleanValue() == english) {
            return;
        }
        lastEnglishState = Boolean.valueOf(english);
        final IMManager manager = detect();
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    manager.setEnglishMode(english);
                } catch (Throwable t) {
                    LOG.error("[imblocker] Failed to set conversion mode to english=" + english, t);
                }
            }
        });
    }

    /** Resets the cached transition state, forcing a fresh evaluation. */
    public static void resetCache() {
        lastIMState = null;
        lastEnglishState = null;
    }

    /** Flushes and terminates the platform backend. Called on game shutdown. */
    public static void shutdown() {
        IMManager manager = instance;
        resetCache();
        if (manager != null) {
            try {
                manager.shutdownBackend();
            } catch (Throwable t) {
                LOG.error("[imblocker] Error while shutting down the input method backend.", t);
            }
        }
        WORKER.shutdown();
    }

    /** Human-readable backend name for logging and diagnostics. */
    public abstract String name();

    /** Platform warm-up hook, called once on the worker thread. */
    public abstract void initialize();

    /** Enables or disables the input method for the game window. */
    protected abstract void setIMEnabled(boolean enabled);

    /** Selects the English (true) or native CJK (false) conversion mode. */
    protected abstract void setEnglishMode(boolean english);

    /** Releases any resources held by the backend. */
    protected abstract void shutdownBackend();
}
