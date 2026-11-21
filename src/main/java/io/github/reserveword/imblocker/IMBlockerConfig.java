package io.github.reserveword.imblocker;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.logging.log4j.Logger;

/**
 * File-based configuration for IMBlocker 1.12.2.
 *
 * <p>Stored as a plain Java {@link Properties} file at {@code config/imblocker.cfg}.
 * A commented template is written on first run. Unknown keys are ignored and the
 * file is never rewritten automatically afterwards, so user comments survive updates.
 */
public final class IMBlockerConfig {

    private IMBlockerConfig() {
    }

    /* defaults */
    private static final boolean DEFAULT_ENABLED = true;
    private static final boolean DEFAULT_DEBUG = false;
    private static final String DEFAULT_COMMAND_PREFIX = "^/";
    private static final boolean DEFAULT_ENGLISH_FOR_COMMANDS = true;
    private static final String DEFAULT_IBUS_ON = "libpinyin";
    private static final String DEFAULT_IBUS_OFF = "xkb:us::eng";
    private static final String DEFAULT_IBUS_ON_STATE = "libpinyin";
    private static final String DEFAULT_FCITX5_ON = "-o";
    private static final String DEFAULT_FCITX5_OFF = "-c";
    private static final String DEFAULT_FCITX_ON = "-o";
    private static final String DEFAULT_FCITX_OFF = "-c";
    /** Screens that bypass the GuiTextField scan because they handle keys directly. */
    private static final String DEFAULT_EXTRA_TYPING_SCREENS =
            "net.minecraft.client.gui.inventory.GuiEditSign,"
            + "net.minecraft.client.gui.inventory.GuiEditStructure,"
            + "net.minecraft.client.gui.inventory.GuiEditCommandBlockMinecart,"
            + "net.minecraft.client.gui.GuiScreenBook";

    private static final Logger LOG = IMBlocker.LOGGER;

    private static File configFile;
    private static Properties properties;

    private static volatile boolean enabled = DEFAULT_ENABLED;
    private static volatile boolean debug = DEFAULT_DEBUG;
    private static volatile boolean englishForCommands = DEFAULT_ENGLISH_FOR_COMMANDS;
    private static volatile String commandPrefix = DEFAULT_COMMAND_PREFIX;
    private static volatile Pattern commandPattern;
    private static volatile String ibusOnArg = DEFAULT_IBUS_ON;
    private static volatile String ibusOffArg = DEFAULT_IBUS_OFF;
    private static volatile String ibusOnState = DEFAULT_IBUS_ON_STATE;
    private static volatile String fcitx5OnArg = DEFAULT_FCITX5_ON;
    private static volatile String fcitx5OffArg = DEFAULT_FCITX5_OFF;
    private static volatile String fcitxOnArg = DEFAULT_FCITX_ON;
    private static volatile String fcitxOffArg = DEFAULT_FCITX_OFF;
    private static volatile List<String> extraTypingScreens = parseScreenList(DEFAULT_EXTRA_TYPING_SCREENS);

    /**
     * Loads the configuration from the given file. Missing file is created with
     * documented defaults. Invalid values fall back to defaults individually.
     */
    public static synchronized void load(File file) {
        configFile = file;
        Properties fallback = defaults();
        Properties loaded = new Properties();

        if (!file.exists()) {
            writeTemplate(file);
        }

        Reader reader = null;
        try {
            reader = new InputStreamReader(new FileInputStream(file), "UTF-8");
            loaded.load(reader);
        } catch (IOException e) {
            LOG.error("[imblocker] Failed to read config " + file.getAbsolutePath() + ", using defaults", e);
            loaded = new Properties();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }

        for (String key : fallback.stringPropertyNames()) {
            if (loaded.getProperty(key) == null) {
                loaded.setProperty(key, fallback.getProperty(key));
            }
        }

        enabled = getBoolean(loaded, "enabled", DEFAULT_ENABLED);
        debug = getBoolean(loaded, "debug", DEFAULT_DEBUG);
        englishForCommands = getBoolean(loaded, "englishForCommands", DEFAULT_ENGLISH_FOR_COMMANDS);
        commandPrefix = getTrimmed(loaded, "commandPrefix", DEFAULT_COMMAND_PREFIX);
        ibusOnArg = getTrimmed(loaded, "ibusOnArg", DEFAULT_IBUS_ON);
        ibusOffArg = getTrimmed(loaded, "ibusOffArg", DEFAULT_IBUS_OFF);
        ibusOnState = getTrimmed(loaded, "ibusOnState", DEFAULT_IBUS_ON_STATE);
        fcitx5OnArg = getTrimmed(loaded, "fcitx5OnArg", DEFAULT_FCITX5_ON);
        fcitx5OffArg = getTrimmed(loaded, "fcitx5OffArg", DEFAULT_FCITX5_OFF);
        fcitxOnArg = getTrimmed(loaded, "fcitxOnArg", DEFAULT_FCITX_ON);
        fcitxOffArg = getTrimmed(loaded, "fcitxOffArg", DEFAULT_FCITX_OFF);
        extraTypingScreens = parseScreenList(getTrimmed(loaded, "extraTypingScreens", DEFAULT_EXTRA_TYPING_SCREENS));
        commandPattern = compileCommandPattern(commandPrefix);

        properties = loaded;
        LOG.info("[imblocker] Configuration loaded (enabled={}, debug={}, commandPrefix=\"{}\", englishForCommands={})",
                enabled, debug, commandPrefix, englishForCommands);
    }

    private static Pattern compileCommandPattern(String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            LOG.error("[imblocker] Invalid commandPrefix regular expression \"" + pattern + "\", falling back to \"" + DEFAULT_COMMAND_PREFIX + "\"", e);
            return Pattern.compile(DEFAULT_COMMAND_PREFIX);
        }
    }

    private static List<String> parseScreenList(String raw) {
        List<String> result = new ArrayList<String>();
        if (raw != null) {
            String[] parts = raw.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.length() > 0) {
                    result.add(trimmed);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns true when the given text starts with the configured command prefix
     * (default {@code ^/}), meaning the player is probably typing a command and
     * the IME conversion mode should switch to English/half-width.
     */
    public static boolean isCommand(String text) {
        if (text == null) {
            return false;
        }
        Pattern pattern = commandPattern;
        if (pattern == null) {
            return text.trim().startsWith("/");
        }
        Matcher matcher = pattern.matcher(text.trim());
        return matcher.find();
    }

    /**
     * Returns true when the given screen class name (or one of its parents) is
     * whitelisted as a screen that accepts text input directly instead of
     * through a GuiTextField.
     */
    public static boolean isTypingScreen(String className) {
        if (className == null) {
            return false;
        }
        List<String> screens = extraTypingScreens;
        for (int i = 0; i < screens.size(); i++) {
            if (screens.get(i).equals(className)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isDebug() {
        return debug;
    }

    public static boolean isEnglishForCommands() {
        return englishForCommands;
    }

    public static String getIbusOnArg() {
        return ibusOnArg;
    }

    public static String getIbusOffArg() {
        return ibusOffArg;
    }

    public static String getIbusOnState() {
        return ibusOnState;
    }

    public static String getFcitx5OnArg() {
        return fcitx5OnArg;
    }

    public static String getFcitx5OffArg() {
        return fcitx5OffArg;
    }

    public static String getFcitxOnArg() {
        return fcitxOnArg;
    }

    public static String getFcitxOffArg() {
        return fcitxOffArg;
    }

    private static boolean getBoolean(Properties props, String key, boolean def) {
        String value = props.getProperty(key);
        return value == null ? def : Boolean.parseBoolean(value.trim());
    }

    private static String getTrimmed(Properties props, String key, String def) {
        String value = props.getProperty(key);
        if (value == null) {
            return def;
        }
        value = value.trim();
        return value.length() == 0 ? def : value;
    }

    private static Properties defaults() {
        Properties props = new Properties();
        props.setProperty("enabled", String.valueOf(DEFAULT_ENABLED));
        props.setProperty("debug", String.valueOf(DEFAULT_DEBUG));
        props.setProperty("englishForCommands", String.valueOf(DEFAULT_ENGLISH_FOR_COMMANDS));
        props.setProperty("commandPrefix", DEFAULT_COMMAND_PREFIX);
        props.setProperty("ibusOnArg", DEFAULT_IBUS_ON);
        props.setProperty("ibusOffArg", DEFAULT_IBUS_OFF);
        props.setProperty("ibusOnState", DEFAULT_IBUS_ON_STATE);
        props.setProperty("fcitx5OnArg", DEFAULT_FCITX5_ON);
        props.setProperty("fcitx5OffArg", DEFAULT_FCITX5_OFF);
        props.setProperty("fcitxOnArg", DEFAULT_FCITX_ON);
        props.setProperty("fcitxOffArg", DEFAULT_FCITX_OFF);
        props.setProperty("extraTypingScreens", DEFAULT_EXTRA_TYPING_SCREENS);
        return props;
    }

    private static void writeTemplate(File file) {
        Writer writer = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                // best effort; the write below will report the failure
            }
            writer = new OutputStreamWriter(new FileOutputStream(file), Charset.forName("UTF-8"));
            writer.write(
                    "# IMBlocker for Forge 1.12.2 - configuration\n"
                    + "# 在你不打字的时候自动关闭输入法，打字时自动开启。\n"
                    + "# Delete this file to regenerate the documented defaults, or edit values and restart the game.\n"
                    + "\n"
                    + "# Master switch. Set to false to suspend all input method management.\n"
                    + "enabled=true\n"
                    + "\n"
                    + "# Verbose logging of every focus decision. Useful for diagnosing incompatibilities.\n"
                    + "debug=false\n"
                    + "\n"
                    + "# Regular expression matched against the trimmed content of the focused text field.\n"
                    + "# Reserved for future Windows support: command recognition uses this prefix.\n"
                    + "# The current Linux backend does not switch command conversion modes.\n"
                    + "commandPrefix=^/\n"
                    + "\n"
                    + "# Set to false to always keep the user's conversion mode unchanged.\n"
                    + "englishForCommands=true\n"
                    + "\n"
                    + "# Linux (IBus) settings. The given arguments are passed to `ibus engine <arg>`.\n"
                    + "ibusOnArg=libpinyin\n"
                    + "ibusOffArg=xkb:us::eng\n"
                    + "ibusOnState=libpinyin\n"
                    + "\n"
                    + "# Linux (Fcitx 5 / Fcitx 4) settings. -o/-c open/close the input method.\n"
                    + "fcitx5OnArg=-o\n"
                    + "fcitx5OffArg=-c\n"
                    + "fcitxOnArg=-o\n"
                    + "fcitxOffArg=-c\n"
                    + "\n"
                    + "# Additional screens that accept text input directly (comma separated class names).\n"
                    + "# Screens that use GuiTextField are detected automatically; only add screens here\n"
                    + "# when they receive key events without any text field.\n"
                    + "extraTypingScreens=" + DEFAULT_EXTRA_TYPING_SCREENS + "\n");
            writer.flush();
        } catch (IOException e) {
            LOG.error("[imblocker] Unable to write default config " + file.getAbsolutePath(), e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }
}
