package io.github.reserveword.imblocker.platform;

import io.github.reserveword.imblocker.IMManager;

/** Windows support is intentionally disabled until a same-thread native bridge is available. */
public final class WindowsIMManager extends IMManager {
    public String name() { return "Windows (unsupported)"; }
    public void initialize() {
        LOG.warn("[imblocker] Windows IME integration is not implemented in this port; input method management is disabled.");
    }
    protected void setIMEnabled(boolean enabled) { }
    protected void setEnglishMode(boolean english) { }
    protected void shutdownBackend() { }
}
