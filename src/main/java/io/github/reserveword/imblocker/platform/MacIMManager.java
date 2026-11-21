package io.github.reserveword.imblocker.platform;

import io.github.reserveword.imblocker.IMManager;

/**
 * macOS backend.
 *
 * <p>The original IMBlocker controls the input method on macOS through the
 * InputMethodKit framework (via a JNI bridge compiled for Apple silicon and
 * Intel). That requires bundling native code, which this pure-Java port
 * deliberately avoids: 1.12.2 is a legacy platform and shipping
 * unverifiable native libraries is worse than not supporting macOS yet.
 *
 * <p>The backend therefore disables itself with a clear warning. All the other
 * features of the mod (configuration, toggle key, screen detection) still load;
 * only the actual IME switching is inactive.
 *
 * <p>See the README "macOS support" section for a working manual alternative
 * (the system-wide input source switch shortcut) and for how to contribute a
 * real backend.
 */
public final class MacIMManager extends IMManager {

    private volatile boolean warned;

    @Override
    public String name() {
        return "macOS (unsupported, inactive)";
    }

    @Override
    public void initialize() {
        warnOnce();
    }

    @Override
    protected void setIMEnabled(boolean enabled) {
        warnOnce();
    }

    @Override
    protected void setEnglishMode(boolean english) {
        warnOnce();
    }

    @Override
    protected void shutdownBackend() {
        // nothing to release
    }

    private void warnOnce() {
        if (!warned) {
            warned = true;
            LOG.warn("[imblocker] macOS is not supported by this port: the original implementation requires the "
                    + "InputMethodKit framework, which needs native code. Input method management is inactive; "
                    + "everything else works normally. See the README for a workaround and contribution hints.");
        }
    }
}
