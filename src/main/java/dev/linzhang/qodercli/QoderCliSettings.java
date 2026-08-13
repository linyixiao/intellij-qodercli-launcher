package dev.linzhang.qodercli;

import com.intellij.ide.util.PropertiesComponent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Application-wide settings for this plugin, persisted through {@link PropertiesComponent} (the
 * same mechanism the "继续当前会话" toggle uses), so there is no service to register and the values
 * survive IDE restarts and are shared across projects.
 *
 * <p>The only setting so far is <b>which terminal to launch the CLI in</b>: the IDE's embedded
 * terminal (the default), an external emulator, or a user-supplied command template.
 */
final class QoderCliSettings {

    private static final String MODE_KEY = "dev.linzhang.qodercli.terminal.mode";
    private static final String TEMPLATE_KEY = "dev.linzhang.qodercli.terminal.customTemplate";

    private QoderCliSettings() {
    }

    /**
     * Where a Qoder CLI session is opened. A fixed set of choices rather than free text, so the
     * common cases work with no configuration; {@link #CUSTOM} is the escape hatch for everything
     * else (Warp, kitty, WezTerm, Alacritty, …).
     */
    enum Mode {

        /** The IDE's own terminal tool window. Keeps session reuse and IDE integration. */
        BUILT_IN("IDE 内置终端（默认）"),

        /** iTerm2 on macOS, driven through AppleScript. */
        ITERM2("iTerm2（macOS）"),

        /**
         * Ghostty on macOS and Linux. Sessions cannot be scripted after the fact (Ghostty exposes
         * no equivalent of iTerm's session ids), so follow-up questions open a new window and
         * resume with {@code --continue}.
         */
        GHOSTTY("Ghostty"),

        /**
         * The platform's stock terminal: Terminal.app on macOS, Windows Terminal (falling back to
         * {@code cmd}) on Windows, and the first emulator found on Linux.
         */
        SYSTEM("系统默认终端"),

        /** A user-supplied command line with {@code {dir}} / {@code {cmd}} / {@code {script}} placeholders. */
        CUSTOM("自定义命令");

        private final String label;

        Mode(@NotNull String label) {
            this.label = label;
        }

        /** The Chinese text shown in the settings drop-down. */
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * The configured mode. Unknown or missing values (e.g. a setting written by a newer build)
     * fall back to {@link Mode#BUILT_IN} rather than failing.
     */
    static @NotNull Mode mode() {
        String stored = PropertiesComponent.getInstance().getValue(MODE_KEY);
        if (stored == null) {
            return Mode.BUILT_IN;
        }
        try {
            return Mode.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return Mode.BUILT_IN;
        }
    }

    /**
     * The mode actually used at launch time. {@link Mode#ITERM2} and {@link Mode#GHOSTTY} only mean
     * something where they exist, so elsewhere they degrade to {@link Mode#SYSTEM} instead of
     * failing. The stored value is left untouched, so moving the settings back to a machine where
     * the choice applies restores the original selection.
     */
    static @NotNull Mode effectiveMode() {
        Mode mode = mode();
        if (mode == Mode.ITERM2 && !QoderCliLauncher.isMac()) {
            return Mode.SYSTEM;
        }
        if (mode == Mode.GHOSTTY && QoderCliLauncher.isWindows()) {
            return Mode.SYSTEM;
        }
        if (mode == Mode.CUSTOM && customTemplate().isBlank()) {
            return Mode.BUILT_IN;
        }
        return mode;
    }

    static void setMode(@NotNull Mode mode) {
        PropertiesComponent.getInstance().setValue(MODE_KEY, mode.name(), Mode.BUILT_IN.name());
    }

    /** The custom command template, or an empty string when the user has not set one. */
    static @NotNull String customTemplate() {
        String stored = PropertiesComponent.getInstance().getValue(TEMPLATE_KEY);
        return stored == null ? "" : stored;
    }

    static void setCustomTemplate(@NotNull String template) {
        PropertiesComponent.getInstance().setValue(TEMPLATE_KEY, template.trim(), "");
    }

    /**
     * The drop-down entries for this platform: iTerm2 is offered on macOS only, Ghostty on macOS
     * and Linux (there is no Windows build). If the user somehow has a value that is not offered
     * here (settings synced from another OS), the caller adds it back so the current choice stays
     * visible and selected instead of being silently reset.
     */
    static @NotNull List<Mode> availableModes() {
        List<Mode> modes = new ArrayList<>();
        modes.add(Mode.BUILT_IN);
        if (QoderCliLauncher.isMac()) {
            modes.add(Mode.ITERM2);
        }
        if (!QoderCliLauncher.isWindows()) {
            modes.add(Mode.GHOSTTY);
        }
        modes.add(Mode.SYSTEM);
        modes.add(Mode.CUSTOM);
        return modes;
    }
}
