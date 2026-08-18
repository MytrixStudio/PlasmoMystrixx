package su.plo.voice.client.config.control;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class ClientControlConfig {

    private final Map<String, Rule> tabs = new HashMap<>();
    private final Map<String, Rule> controls = new HashMap<>();

    private boolean showRestrictedControls = true;
    private boolean showLockTooltip = true;
    private String lockedTooltipKey = "gui.mytrixvoice.server_controlled";

    public static @NotNull ClientControlConfig defaults() {
        return new ClientControlConfig();
    }

    public static @NotNull ClientControlConfig parse(@Nullable String yaml) {
        ClientControlConfig config = defaults();
        if (yaml == null || yaml.trim().isEmpty()) return config;

        String section = "";
        for (String rawLine : yaml.split("\\r?\\n")) {
            String line = stripComment(rawLine);
            if (line.trim().isEmpty()) continue;

            String trimmed = line.trim();
            if (!Character.isWhitespace(line.charAt(0)) && trimmed.endsWith(":")) {
                section = trimmed.substring(0, trimmed.length() - 1).trim().toLowerCase(Locale.ROOT);
                continue;
            }

            int separator = trimmed.indexOf(':');
            if (separator < 0) continue;

            String key = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();

            switch (section) {
                case "settings":
                    config.readSetting(key, value);
                    break;
                case "tabs":
                    config.tabs.put(key, parseRule(value));
                    break;
                case "controls":
                    config.controls.put(key, parseRule(value));
                    break;
                default:
                    break;
            }
        }

        return config;
    }

    public boolean isTabVisible(@NotNull String id) {
        Rule rule = tabs.getOrDefault(id, Rule.DEFAULT);
        return rule.visible && (rule.enabled || showRestrictedControls);
    }

    public boolean isTabEnabled(@NotNull String id) {
        return tabs.getOrDefault(id, Rule.DEFAULT).enabled;
    }

    public boolean isControlVisible(@NotNull String... ids) {
        Rule rule = findControl(ids);
        return rule.visible && (rule.enabled || showRestrictedControls);
    }

    public boolean isControlEnabled(@NotNull String... ids) {
        return findControl(ids).enabled;
    }

    public boolean shouldShowLockTooltip() {
        return showLockTooltip;
    }

    public @NotNull String getLockedTooltipKey() {
        return lockedTooltipKey;
    }

    private @NotNull Rule findControl(@NotNull String... ids) {
        for (String id : ids) {
            Rule rule = controls.get(id);
            if (rule != null) return rule;
        }

        return Rule.DEFAULT;
    }

    private void readSetting(@NotNull String key, @NotNull String value) {
        switch (key) {
            case "show_restricted_controls":
                this.showRestrictedControls = parseBoolean(value, true);
                break;
            case "show_lock_tooltip":
                this.showLockTooltip = parseBoolean(value, true);
                break;
            case "locked_tooltip_key":
                this.lockedTooltipKey = unquote(value);
                break;
            default:
                break;
        }
    }

    private static @NotNull Rule parseRule(@NotNull String value) {
        Rule rule = new Rule();
        String inline = value.trim();
        if (inline.startsWith("{") && inline.endsWith("}")) {
            inline = inline.substring(1, inline.length() - 1);
        }

        for (String part : inline.split(",")) {
            int separator = part.indexOf(':');
            if (separator < 0) continue;

            String key = part.substring(0, separator).trim();
            String entryValue = part.substring(separator + 1).trim();
            switch (key) {
                case "visible":
                    rule.visible = parseBoolean(entryValue, true);
                    break;
                case "enabled":
                    rule.enabled = parseBoolean(entryValue, true);
                    break;
                case "force":
                    rule.force = unquote(entryValue);
                    break;
                default:
                    break;
            }
        }

        return rule;
    }

    private static boolean parseBoolean(@NotNull String value, boolean fallback) {
        String normalized = unquote(value).toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) return true;
        if ("false".equals(normalized)) return false;
        return fallback;
    }

    private static @NotNull String unquote(@NotNull String value) {
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
                (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }

        return trimmed;
    }

    private static @NotNull String stripComment(@NotNull String line) {
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if ((c == '"' || c == '\'') && (i == 0 || line.charAt(i - 1) != '\\')) {
                if (!quoted) {
                    quoted = true;
                    quote = c;
                } else if (quote == c) {
                    quoted = false;
                }
            } else if (c == '#' && !quoted) {
                return line.substring(0, i);
            }
        }

        return line;
    }

    public static final class Rule {
        private static final Rule DEFAULT = new Rule();

        private boolean visible = true;
        private boolean enabled = true;
        private @Nullable String force;
    }
}
