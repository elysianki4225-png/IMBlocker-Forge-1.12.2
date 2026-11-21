package io.github.reserveword.imblocker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Logger;

/**
 * Reflection facade over the obfuscation-sensitive classes of Minecraft 1.12.2.
 *
 * <p>Minecraft 1.12.2 is obfuscated in production. The only difference between a
 * ForgeGradle development workspace and production is the naming scheme of
 * methods and fields (MCP names in dev, SRG names in production). Class names
 * themselves are stable ({@code net.minecraft.client.gui.GuiTextField}, ...).
 *
 * <p>Instead of referencing Minecraft classes at compile time (which would force
 * the produced jar through a deobfuscation/reobfuscation step), every sensitive
 * member is resolved reflectively at runtime, trying both MCP and SRG names.
 * As a result the jar works identically in dev workspaces and in production, and
 * no remapper is ever required.
 *
 * <p>All methods degrade gracefully: when resolution fails (for example on a
 * dedicated server without client classes) they return sensible defaults and the
 * mod disables itself instead of crashing the game.
 */
public final class McAccess {

    private static final Logger LOG = IMBlocker.LOGGER;

    /** Stable (unobfuscated) class names in 1.12.2. */
    private static final String MC_MINECRAFT_CLASS = "net.minecraft.client.Minecraft";
    private static final String MC_GUI_TEXT_FIELD_CLASS = "net.minecraft.client.gui.GuiTextField";

    /** [MCP name, SRG name] candidates, dev first then production. */
    private static final String[] GET_MINECRAFT_NAMES = {"getMinecraft", "func_71410_x"};
    private static final String[] CURRENT_SCREEN_FIELDS = {"currentScreen", "field_71462_r"};
    private static final String[] IS_FOCUSED_METHODS = {"isFocused", "func_146206_l"};
    private static final String[] GET_TEXT_METHODS = {"getText", "func_146179_b"};

    /** null until successfully resolved; cached forever afterwards. */
    private static Method getMinecraftMethod;
    private static Field currentScreenField;
    private static Class<?> guiTextFieldClass;
    private static Method isFocusedMethod;
    private static Method getTextMethod;

    /** Set once resolution failed, to avoid spamming the log every tick. */
    private static boolean minecraftResolutionFailed;
    private static boolean textFieldResolutionFailed;
    private static boolean resolutionWarningLogged;

    private McAccess() {
    }

    /**
     * Returns the Minecraft client singleton, or null when unavailable
     * (dedicated server, or an unexpected runtime environment).
     */
    public static Object theMinecraft() {
        if (minecraftResolutionFailed) {
            return null;
        }
        try {
            if (getMinecraftMethod == null) {
                synchronized (McAccess.class) {
                    if (getMinecraftMethod == null) {
                        Class<?> mc = Class.forName(MC_MINECRAFT_CLASS);
                        getMinecraftMethod = findStaticMethod(mc, GET_MINECRAFT_NAMES);
                    }
                }
            }
            if (getMinecraftMethod == null) {
                failMinecraftResolution(new NoSuchMethodException("Minecraft#getMinecraft() not found under any known name"));
                return null;
            }
            return getMinecraftMethod.invoke(null);
        } catch (Throwable t) {
            failMinecraftResolution(t);
            return null;
        }
    }

    /**
     * Returns the current {@code GuiScreen}, or null when the player is in-game
     * (no GUI) or when Minecraft cannot be resolved.
     */
    public static Object currentScreen() {
        Object mc = theMinecraft();
        if (mc == null) {
            throw new IllegalStateException("Minecraft client could not be resolved");
        }
        try {
            if (currentScreenField == null) {
                synchronized (McAccess.class) {
                    if (currentScreenField == null) {
                        currentScreenField = findField(mc.getClass(), CURRENT_SCREEN_FIELDS);
                    }
                }
            }
            if (currentScreenField == null) {
                throw new IllegalStateException("Minecraft current screen field could not be resolved");
            }
            return currentScreenField.get(mc);
        } catch (Throwable t) {
            if (isDebug()) {
                LOG.error("[imblocker] Unable to read current screen", t);
            }
            throw new IllegalStateException("Unable to read Minecraft current screen", t);
        }
    }

    /**
     * Returns the class object for {@code net.minecraft.client.gui.GuiTextField},
     * or null when it cannot be loaded.
     */
    public static Class<?> guiTextFieldClass() {
        if (textFieldResolutionFailed) {
            return null;
        }
        try {
            if (guiTextFieldClass == null) {
                synchronized (McAccess.class) {
                    if (guiTextFieldClass == null) {
                        guiTextFieldClass = Class.forName(MC_GUI_TEXT_FIELD_CLASS);
                    }
                }
            }
            return guiTextFieldClass;
        } catch (Throwable t) {
            textFieldResolutionFailed = true;
            warnOnce("GuiTextField could not be resolved; only whitelist screens will enable the IME.", t);
            return null;
        }
    }

    /**
     * Finds all text field instances stored in the given screen (walking the
     * whole class hierarchy). Instances of GuiTextField subclasses (common in
     * modded GUI frameworks) are included.
     */
    public static List<Object> findTextFields(Object screen) {
        List<Object> result = new ArrayList<Object>();
        Class<?> tfClass = guiTextFieldClass();
        if (screen == null || tfClass == null) {
            return result;
        }
        Class<?> cls = screen.getClass();
        while (cls != null && cls != Object.class) {
            Field[] fields = cls.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                Field field = fields[i];
                Class<?> type = field.getType();
                if (!tfClass.isAssignableFrom(type)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(screen);
                    if (value != null) {
                        result.add(value);
                    }
                } catch (Throwable t) {
                    // inaccessible field: skip silently, other fields may still work
                    if (isDebug()) {
                        LOG.warn("[imblocker] Unable to read text field " + field, t);
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        return result;
    }

    /**
     * Returns the first text field of the screen that is currently focused, or
     * null. This is the field the player is actually typing into.
     */
    public static Object findFocusedTextField(Object screen) {
        List<Object> fields = findTextFields(screen);
        for (int i = 0; i < fields.size(); i++) {
            if (isTextFieldFocused(fields.get(i))) {
                return fields.get(i);
            }
        }
        return null;
    }

    /** Returns true when the given text field reports focus. */
    public static boolean isTextFieldFocused(Object textField) {
        if (textField == null) {
            return false;
        }
        try {
            if (isFocusedMethod == null) {
                synchronized (McAccess.class) {
                    if (isFocusedMethod == null) {
                        isFocusedMethod = findInstanceMethod(textField.getClass(), IS_FOCUSED_METHODS);
                    }
                }
            }
            if (isFocusedMethod == null) {
                return false;
            }
            Object result = isFocusedMethod.invoke(textField);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            if (isDebug()) {
                LOG.warn("[imblocker] isFocused() invocation failed", t);
            }
            return false;
        }
    }

    /** Returns the current content of the given text field, or null on failure. */
    public static String getTextFieldText(Object textField) {
        if (textField == null) {
            return null;
        }
        try {
            if (getTextMethod == null) {
                synchronized (McAccess.class) {
                    if (getTextMethod == null) {
                        getTextMethod = findInstanceMethod(textField.getClass(), GET_TEXT_METHODS);
                    }
                }
            }
            if (getTextMethod == null) {
                return null;
            }
            Object result = getTextMethod.invoke(textField);
            return result instanceof String ? (String) result : null;
        } catch (Throwable t) {
            if (isDebug()) {
                LOG.warn("[imblocker] getText() invocation failed", t);
            }
            return null;
        }
    }

    /**
     * Checks whether the given class name (or one of its super types) matches
     * the whitelisted direct-input screens from the configuration.
     */
    public static boolean isWhitelistedTypingScreen(Object screen) {
        if (screen == null) {
            return false;
        }
        Class<?> cls = screen.getClass();
        while (cls != null && cls != Object.class) {
            if (IMBlockerConfig.isTypingScreen(cls.getName())) {
                return true;
            }
            cls = cls.getSuperclass();
        }
        return false;
    }

    /** Debug hook used by the smoke tests. */
    static boolean resolutionWorked() {
        return !minecraftResolutionFailed;
    }

    private static boolean isDebug() {
        return IMBlockerConfig.isDebug();
    }

    private static Method findStaticMethod(Class<?> cls, String[] candidates) {
        for (int i = 0; i < candidates.length; i++) {
            try {
                Method method = cls.getDeclaredMethod(candidates[i]);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException e) {
                // try next candidate name
            } catch (Throwable t) {
                if (isDebug()) {
                    LOG.warn("[imblocker] Unable to access static method " + cls.getName() + "#" + candidates[i], t);
                }
            }
        }
        return null;
    }

    private static Method findInstanceMethod(Class<?> cls, String[] candidates) {
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            for (int i = 0; i < candidates.length; i++) {
                try {
                    Method method = current.getDeclaredMethod(candidates[i]);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException e) {
                    // try next candidate on this class, then walk up
                } catch (Throwable t) {
                    if (isDebug()) {
                        LOG.warn("[imblocker] Unable to access method " + current.getName() + "#" + candidates[i], t);
                    }
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Field findField(Class<?> cls, String[] candidates) {
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            for (int i = 0; i < candidates.length; i++) {
                try {
                    Field field = current.getDeclaredField(candidates[i]);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException e) {
                    // try next candidate on this class, then walk up
                } catch (Throwable t) {
                    if (isDebug()) {
                        LOG.warn("[imblocker] Unable to access field " + current.getName() + "#" + candidates[i], t);
                    }
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static void failMinecraftResolution(Throwable t) {
        minecraftResolutionFailed = true;
        warnOnce("Minecraft client classes could not be resolved. IMBlocker will stay inactive. "
                + "This is expected on a dedicated server; otherwise please report the issue with the log below.", t);
    }

    private static void warnOnce(String message, Throwable t) {
        if (resolutionWarningLogged) {
            LOG.debug("[imblocker] " + message, t);
            return;
        }
        resolutionWarningLogged = true;
        LOG.error("[imblocker] " + message, t);
    }
}
