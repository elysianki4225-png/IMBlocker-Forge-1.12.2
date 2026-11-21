package io.github.reserveword.imblocker.platform;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

import io.github.reserveword.imblocker.IMBlockerConfig;
import io.github.reserveword.imblocker.IMManager;

/**
 * Linux backend: switches the input method through the {@code fcitx5-remote},
 * {@code fcitx-remote} or {@code ibus} command line tools.
 *
 * <p>This mirrors the approach of the original IMBlocker on Linux, which also
 * drives those commands instead of talking to Fcitx/IBus over D-Bus directly.
 * The framework (Fcitx 5, Fcitx 4 or IBus) is auto-detected once at startup;
 * when no supported framework is found the backend disables itself and the rest
 * of the game is unaffected.
 *
 * <p>The English conversion mode has no equivalent on Linux in the original
 * mod either, so {@link #setEnglishMode(boolean)} is a no-op here.
 */
public final class LinuxIMManager extends IMManager {

    private enum Framework {
        FCITX5, FCITX4, IBUS, NONE
    }

    private volatile Framework framework;

    @Override
    public String name() {
        Framework f = framework;
        return "Linux IME commands (" + (f == null ? "detecting" : f) + ")";
    }

    @Override
    public void initialize() {
        Framework f = ensureFramework();
        if (f == Framework.NONE) {
            LOG.warn("[imblocker] Neither fcitx5-remote, fcitx-remote nor ibus was detected. "
                    + "Input method management is disabled on this system.");
        } else {
            LOG.info("[imblocker] Linux input method framework: " + f);
        }
    }

    @Override
    protected void setIMEnabled(boolean enabled) {
        Framework f = ensureFramework();
        switch (f) {
            case FCITX5:
                run(new String[]{"fcitx5-remote", enabled ? IMBlockerConfig.getFcitx5OnArg() : IMBlockerConfig.getFcitx5OffArg()});
                break;
            case FCITX4:
                run(new String[]{"fcitx-remote", enabled ? IMBlockerConfig.getFcitxOnArg() : IMBlockerConfig.getFcitxOffArg()});
                break;
            case IBUS:
                run(new String[]{"ibus", "engine", enabled ? IMBlockerConfig.getIbusOnArg() : IMBlockerConfig.getIbusOffArg()});
                break;
            default:
                break;
        }
    }

    @Override
    protected void setEnglishMode(boolean english) {
        // The original IMBlocker has no Linux implementation of the conversion
        // mode either; Linux users switch conversion mode with their own hotkey.
    }

    @Override
    protected void shutdownBackend() {
        // nothing to release; every command is fire-and-forget
    }

    private Framework ensureFramework() {
        Framework f = framework;
        if (f == null) {
            f = detectFramework();
            framework = f;
        }
        return f;
    }

    private Framework detectFramework() {
        if (isRunnable(new String[]{"fcitx5-remote", "--version"})) {
            return Framework.FCITX5;
        }
        if (isRunnable(new String[]{"fcitx-remote", "-V"})) {
            return Framework.FCITX4;
        }
        if (isRunnable(new String[]{"ibus", "--version"})) {
            return Framework.IBUS;
        }
        // last-ditch check matching the original IMBlocker's fcitx5 heuristic
        String fcitx5 = firstLine(runQuiet(new String[]{"pgrep", "-l", "fcitx5"}, 2000));
        if (fcitx5 != null && fcitx5.length() > 0) {
            return Framework.FCITX5;
        }
        return Framework.NONE;
    }

    /** True when the command can be started and either prints something or exits cleanly. */
    private static boolean isRunnable(String[] command) {
        CommandResult result = runQuiet(command, 3000);
        return result != null;
    }

    private static String firstLine(CommandResult result) {
        return result == null ? null : result.firstLine;
    }

    private static void run(String[] command) {
        CommandResult result = runQuiet(command, 3000);
        if (result == null) {
            if (IMBlockerConfig.isDebug()) {
                LOG.warn("[imblocker] Failed to run: " + join(command));
            }
        } else if (IMBlockerConfig.isDebug()) {
            LOG.info("[imblocker] Ran: " + join(command) + " (exit " + result.exitCode + ")");
        }
    }

    private static String join(String[] parts) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(parts[i]);
        }
        return builder.toString();
    }

    /**
     * Runs a command with a hard timeout. Never throws: returns null when the
     * command cannot be started at all, otherwise the (possibly empty) first
     * line of combined output and the exit code. Never blocks longer than
     * {@code timeoutMillis}, even if the child neither exits nor prints.
     */
    private static CommandResult runQuiet(String[] command, long timeoutMillis) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.forName("UTF-8")));
            long deadline = System.currentTimeMillis() + timeoutMillis;
            String line = null;
            Integer exit = null;
            while (System.currentTimeMillis() < deadline) {
                if (exit == null) {
                    exit = pollExit(process);
                }
                if (line == null && reader.ready()) {
                    line = reader.readLine();
                }
                if (exit != null) {
                    // process ended; drain anything still buffered
                    if (line == null && reader.ready()) {
                        line = reader.readLine();
                    }
                    break;
                }
                try {
                    Thread.sleep(15L);
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (exit == null) {
                exit = pollExit(process);
                if (exit == null) {
                    process.destroy();
                    exit = Integer.valueOf(-1);
                }
            }
            CommandResult result = new CommandResult();
            result.firstLine = line;
            result.exitCode = exit.intValue();
            return result;
        } catch (Throwable t) {
            if (process != null) {
                process.destroy();
            }
            return null;
        }
    }

    /** Returns null while the process is still running; the exit code otherwise. */
    private static Integer pollExit(Process process) {
        try {
            return Integer.valueOf(process.exitValue());
        } catch (IllegalThreadStateException e) {
            return null;
        }
    }

    private static final class CommandResult {
        private String firstLine;
        private int exitCode;
    }
}
