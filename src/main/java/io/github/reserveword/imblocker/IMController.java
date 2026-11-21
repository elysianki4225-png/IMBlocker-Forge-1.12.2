package io.github.reserveword.imblocker;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.apache.logging.log4j.Logger;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Core decision engine, driven by the client tick event.
 *
 * <p>Once per tick (END phase) this class answers two questions and pushes the
 * result to {@link IMManager}:
 * <ol>
 *     <li>Should the input method be usable right now? Yes when the current
 *         screen has a focused GuiTextField (chat, command blocks, rename
 *         dialogs, ...) or when the current screen is whitelisted as a direct
 *         input screen (signs, books). No everywhere else, including the HUD.</li>
 *     <li>When the player types a command (content of the focused field matches
 *         the configured command prefix, default {@code ^/}) should the
 *         conversion mode be English? On Windows, yes; elsewhere this is a
 *         no-op like in the original mod.</li>
 * </ol>
 *
 * <p>Detection runs without mixins or coremods by scanning the fields of the
 * current screen every tick; the scan is small (a handful of fields) and all
 * reflection handles are cached. See {@link McAccess}.
 *
 * <p>Failure policy: any error inside the decision engine disables input method
 * management for the rest of the session (leaving the IME available, exactly as
 * if the mod were not installed) and logs the cause. The game itself is never
 * disturbed.
 */
public class IMController {

    private static final Logger LOG = IMBlocker.LOGGER;

    /** LWJGL 2 Keyboard.KEY_F8. Registering a custom binding avoids conflicts with vanilla. */
    private static final int KEY_F8 = 64;

    private boolean suspended;
    private boolean modDisabledApplied;
    private boolean hardDisabled;

    private boolean decisionValid;
    private boolean lastTyping;
    private boolean lastEnglish;

    private boolean toggleKeyResolved;
    private Object toggleKey;
    private Method isPressedMethod;

    /**
     * Client tick handler. Registered on the Forge event bus by the mod entry
     * point; public so the Forge event bus can invoke it.
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            // START phase would fire before the GUI logic for this tick runs
            return;
        }
        if (hardDisabled) {
            return;
        }

        try {
            if (IMManager.refreshWindow()) {
                decisionValid = false;
                modDisabledApplied = false;
            }
        } catch (Throwable t) {
            hardDisable(t);
            return;
        }

        if (pollToggleKey()) {
            suspended = !suspended;
            LOG.info("[imblocker] " + (suspended
                    ? "Input method management suspended. Press the toggle key again to resume."
                    : "Input method management resumed."));
            if (!suspended) {
                // force a fresh decision immediately
                decisionValid = false;
                modDisabledApplied = false;
            }
        }

        if (!IMBlockerConfig.isEnabled() || suspended) {
            // Leave the IME usable: no blocking while the mod is off.
            if (!modDisabledApplied) {
                modDisabledApplied = true;
                decisionValid = false;
                IMManager.setState(true);
                IMManager.setEnglishState(false);
            }
            return;
        }

        try {
            evaluate();
        } catch (Throwable t) {
            hardDisable(t);
        }
    }

    /** Computes the desired IME state and applies it on transitions only. */
    private void evaluate() {
        Object screen = McAccess.currentScreen();

        boolean typing = false;
        Object focusedField = null;
        if (screen != null) {
            if (McAccess.isWhitelistedTypingScreen(screen)) {
                typing = true;
            } else {
                focusedField = McAccess.findFocusedTextField(screen);
                typing = focusedField != null;
            }
        }

        boolean english = false;
        if (typing && IMBlockerConfig.isEnglishForCommands() && focusedField != null) {
            String text = McAccess.getTextFieldText(focusedField);
            english = IMBlockerConfig.isCommand(text);
        }

        if (!decisionValid || typing != lastTyping || english != lastEnglish) {
            if (IMBlockerConfig.isDebug()) {
                LOG.info("[imblocker] decision: screen={} typing={} english={} (was typing={} english={})",
                        screen == null ? "<none>" : screen.getClass().getName(),
                        typing, english, lastTyping, lastEnglish);
            }
            IMManager.setState(typing);
            if (typing) {
                IMManager.setEnglishState(english);
            }
            lastTyping = typing;
            lastEnglish = english;
            decisionValid = true;
            modDisabledApplied = false;
        }
    }

    /** Disables the mod for this session, leaving the IME available. */
    private void hardDisable(Throwable t) {
        hardDisabled = true;
        LOG.error("[imblocker] A fatal error occurred in the input method decision engine; "
                + "input method management is disabled for this session. The game is not affected.", t);
        try {
            IMManager.setState(true);
        } catch (Throwable ignored) {
            // nothing more we can do
        }
    }

    /** Registers (once) and polls the configurable toggle key, resolving it reflectively. */
    private boolean pollToggleKey() {
        if (!toggleKeyResolved) {
            resolveToggleKey();
        }
        if (isPressedMethod == null || toggleKey == null) {
            return false;
        }
        try {
            Object result = isPressedMethod.invoke(toggleKey);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            LOG.warn("[imblocker] Toggle key poll failed; the toggle key will be ignored.", t);
            toggleKeyResolved = true;
            toggleKey = null;
            isPressedMethod = null;
            return false;
        }
    }

    private void resolveToggleKey() {
        toggleKeyResolved = true;
        try {
            Class<?> keyBindingClass = Class.forName("net.minecraft.client.settings.KeyBinding");
            Constructor<?> constructor = keyBindingClass.getConstructor(String.class, Integer.TYPE, String.class);
            Object binding = constructor.newInstance("key.imblocker.toggle_im", Integer.valueOf(KEY_F8), "key.categories.imblocker");

            Class<?> clientRegistry = Class.forName("net.minecraftforge.fml.client.registry.ClientRegistry");
            clientRegistry.getMethod("registerKeyBinding", keyBindingClass).invoke(null, binding);

            // KeyBinding#isPressed() (MCP) == KeyBinding#func_151468_f() (SRG)
            isPressedMethod = findMethod(keyBindingClass, "isPressed", "func_151468_f");
            if (isPressedMethod == null) {
                LOG.warn("[imblocker] Unable to resolve KeyBinding#isPressed; toggle key disabled.");
                return;
            }
            isPressedMethod.setAccessible(true);
            toggleKey = binding;
            LOG.info("[imblocker] Toggle key bound to F8 (rebindable in Controls -> IMBlocker).");
        } catch (Throwable t) {
            LOG.warn("[imblocker] Unable to register the toggle key binding; "
                    + "use enabled=false in config/imblocker.cfg to suspend IMBlocker instead.", t);
        }
    }

    private static Method findMethod(Class<?> cls, String... candidates) {
        for (int i = 0; i < candidates.length; i++) {
            try {
                return cls.getDeclaredMethod(candidates[i]);
            } catch (NoSuchMethodException e) {
                // try the next candidate name
            }
        }
        return null;
    }
}
