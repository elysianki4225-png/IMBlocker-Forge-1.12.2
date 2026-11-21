package io.github.reserveword.imblocker.platform;

import java.lang.reflect.Field;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import io.github.reserveword.imblocker.IMManager;

/** IMM calls must run on the thread that owns LWJGL's window: the client tick thread. */
public final class WindowsIMManager extends IMManager {
    private NativeLibrary imm;
    private Function associate, getContext, releaseContext, getConversion, setConversion;
    private Pointer window, detachedContext;
    private boolean detached, unavailable, conversionChanged;
    private int oldConversion, oldSentence;

    public String name() { return "Windows IMM (JNA)"; }
    public void initialize() { /* initialized on the window thread, not the worker */ }

    /** Called every client tick; detects LWJGL window recreation and retries initial resolution. */
    public boolean updateWindow() {
        if (unavailable) return false;
        try {
            Class<?> display = Class.forName("org.lwjgl.opengl.Display");
            Field implField = display.getDeclaredField("display_impl");
            implField.setAccessible(true);
            Object impl = implField.get(null);
            if (impl == null) return false;
            Field hwndField = impl.getClass().getDeclaredField("hwnd");
            hwndField.setAccessible(true);
            long hwnd = ((Long) hwndField.get(impl)).longValue();
            if (hwnd == 0) return false;
            if (window != null && Pointer.nativeValue(window) == hwnd) return false;
            // The old LWJGL window has been destroyed; its context must not be reused.
            detached = false;
            detachedContext = null;
            conversionChanged = false;
            window = new Pointer(hwnd);
            if (imm == null) {
                imm = NativeLibrary.getInstance("imm32");
                associate = imm.getFunction("ImmAssociateContext", Function.ALT_CONVENTION);
                getContext = imm.getFunction("ImmGetContext", Function.ALT_CONVENTION);
                releaseContext = imm.getFunction("ImmReleaseContext", Function.ALT_CONVENTION);
                getConversion = imm.getFunction("ImmGetConversionStatus", Function.ALT_CONVENTION);
                setConversion = imm.getFunction("ImmSetConversionStatus", Function.ALT_CONVENTION);
            }
            return true;
        } catch (Throwable t) {
            unavailable = true;
            LOG.error("[imblocker] Windows IMM initialization failed; IME management disabled.", t);
            return false;
        }
    }

    protected void setIMEnabled(boolean enabled) {
        if (unavailable || window == null) return;
        if (enabled && detached) {
            associate.invokePointer(new Object[]{window, detachedContext});
            detached = false;
            detachedContext = null;
        } else if (!enabled && !detached) {
            restoreConversion();
            Pointer previous = associate.invokePointer(new Object[]{window, null});
            // A null result can also indicate a failed call. Do not invent or destroy a context.
            if (previous != null && Pointer.nativeValue(previous) != 0) {
                detachedContext = previous;
                detached = true;
            }
        }
    }

    protected void setEnglishMode(boolean english) {
        if (unavailable || window == null || detached) return;
        if (!english) { restoreConversion(); return; }
        if (conversionChanged) return;
        Pointer context = getContext.invokePointer(new Object[]{window});
        if (context == null) return;
        try {
            IntByReference conversion = new IntByReference();
            IntByReference sentence = new IntByReference();
            if (getConversion.invokeInt(new Object[]{context, conversion, sentence}) != 0) {
                int previous = conversion.getValue();
                int oldSentenceValue = sentence.getValue();
                // IME_CMODE_NATIVE and IME_CMODE_FULLSHAPE: clear only these bits.
                if (setConversion.invokeInt(new Object[]{context, Integer.valueOf(previous & ~9), Integer.valueOf(oldSentenceValue)}) != 0) {
                    oldConversion = previous;
                    oldSentence = oldSentenceValue;
                    conversionChanged = true;
                }
            }
        } finally { releaseContext.invokeInt(new Object[]{window, context}); }
    }

    private void restoreConversion() {
        if (!conversionChanged || window == null) return;
        Pointer context = getContext.invokePointer(new Object[]{window});
        if (context == null) return;
        try {
            if (setConversion.invokeInt(new Object[]{context, Integer.valueOf(oldConversion), Integer.valueOf(oldSentence)}) != 0)
                conversionChanged = false;
        } finally { releaseContext.invokeInt(new Object[]{window, context}); }
    }

    // Shutdown hook is not the window thread. Windows releases the window and its
    // associations on process exit; do not issue cross-thread IMM calls here.
    protected void shutdownBackend() { }
}
