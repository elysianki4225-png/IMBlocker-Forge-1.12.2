package io.github.reserveword.imblocker.platform;

import io.github.reserveword.imblocker.IMManager;

/**
 * No-op backend used on unsupported platforms or after initialization failed.
 * Keeps the rest of the mod functional while staying completely out of the way.
 */
public final class NoopIMManager extends IMManager {

    private final String reason;

    public NoopIMManager(String reason) {
        this.reason = reason == null ? "unknown" : reason;
    }

    @Override
    public String name() {
        return "No-op (" + reason + ")";
    }

    @Override
    public void initialize() {
        LOG.warn("[imblocker] Input method management is inactive: " + reason);
    }

    @Override
    protected void setIMEnabled(boolean enabled) {
        // no-op
    }

    @Override
    protected void setEnglishMode(boolean english) {
        // no-op
    }

    @Override
    protected void shutdownBackend() {
        // no-op
    }
}
