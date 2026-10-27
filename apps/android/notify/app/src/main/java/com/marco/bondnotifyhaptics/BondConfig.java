package com.marco.bondnotifyhaptics;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class BondConfig {
    static final String PREFS = "bond_notify_haptics";
    static final String KEY_ADDRESS = "address";
    static final String KEY_NAME = "name";
    static final String KEY_AUID = "auid";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_LAST_LOG = "last_log";
    static final String KEY_RULE_PACKAGES = "rule_packages";
    static final String KEY_ALARM_ENABLED = "alarm_enabled";
    static final String KEY_ALARM_REPEAT = "alarm_repeat";
    static final String KEY_ALARM_PATTERN = "alarm_pattern";
    static final String KEY_ALARM_STRENGTH = "alarm_strength";
    static final String KEY_ALARM_DURATION = "alarm_duration";
    static final String KEY_ALARM_COLOR = "alarm_color";
    static final String KEY_ALARM_COOLDOWN = "alarm_cooldown";
    static final String KEY_PRE_ALARM_ENABLED = "pre_alarm_enabled";
    static final String KEY_PRE_ALARM_PATTERN = "pre_alarm_pattern";
    static final String KEY_PRE_ALARM_STRENGTH = "pre_alarm_strength";
    static final String KEY_PRE_ALARM_DURATION = "pre_alarm_duration";
    static final String KEY_PRE_ALARM_COLOR = "pre_alarm_color";
    static final String KEY_PRE_ALARM_COOLDOWN = "pre_alarm_cooldown";
    static final String KEY_QUIET_ENABLED = "quiet_enabled";
    static final String KEY_QUIET_START = "quiet_start";
    static final String KEY_QUIET_END = "quiet_end";

    static final String[] PATTERNS = {
            "single", "double", "triple", "heartbeat", "warning", "long", "long_tap", "breathe"
    };

    private BondConfig() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<AppRule> loadRules(Context context) {
        SharedPreferences prefs = prefs(context);
        Set<String> packages = prefs.getStringSet(KEY_RULE_PACKAGES, Collections.emptySet());
        List<AppRule> rules = new ArrayList<>();
        for (String packageName : packages) {
            rules.add(loadRule(context, packageName));
        }
        rules.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return rules;
    }

    static AppRule loadRule(Context context, String packageName) {
        SharedPreferences prefs = prefs(context);
        String prefix = "rule_" + packageName + "_";
        AppRule rule = new AppRule();
        rule.packageName = packageName;
        rule.label = prefs.getString(prefix + "label", packageName);
        rule.enabled = prefs.getBoolean(prefix + "enabled", true);
        rule.pattern = prefs.getString(prefix + "pattern", "double");
        rule.strength = prefs.getInt(prefix + "strength", 55);
        rule.durationMs = prefs.getInt(prefix + "duration", 90);
        rule.color = prefs.getInt(prefix + "color", 0x37B8FF);
        rule.cooldownMs = prefs.getInt(prefix + "cooldown", 2000);
        rule.priority = prefs.getInt(prefix + "priority", 70);
        sanitize(rule);
        return rule;
    }

    static void saveRule(Context context, AppRule rule) {
        sanitize(rule);
        SharedPreferences prefs = prefs(context);
        Set<String> packages = new HashSet<>(prefs.getStringSet(KEY_RULE_PACKAGES, Collections.emptySet()));
        packages.add(rule.packageName);
        String prefix = "rule_" + rule.packageName + "_";
        prefs.edit()
                .putStringSet(KEY_RULE_PACKAGES, packages)
                .putString(prefix + "label", rule.label)
                .putBoolean(prefix + "enabled", rule.enabled)
                .putString(prefix + "pattern", rule.pattern)
                .putInt(prefix + "strength", rule.strength)
                .putInt(prefix + "duration", rule.durationMs)
                .putInt(prefix + "color", rule.color)
                .putInt(prefix + "cooldown", rule.cooldownMs)
                .putInt(prefix + "priority", rule.priority)
                .apply();
    }

    static void removeRule(Context context, String packageName) {
        SharedPreferences prefs = prefs(context);
        Set<String> packages = new HashSet<>(prefs.getStringSet(KEY_RULE_PACKAGES, Collections.emptySet()));
        packages.remove(packageName);
        String prefix = "rule_" + packageName + "_";
        prefs.edit()
                .putStringSet(KEY_RULE_PACKAGES, packages)
                .remove(prefix + "label")
                .remove(prefix + "enabled")
                .remove(prefix + "pattern")
                .remove(prefix + "strength")
                .remove(prefix + "duration")
                .remove(prefix + "color")
                .remove(prefix + "cooldown")
                .remove(prefix + "priority")
                .apply();
    }

    static AppRule alarmRule(Context context) {
        SharedPreferences prefs = prefs(context);
        AppRule rule = new AppRule();
        rule.packageName = "__alarm__";
        rule.label = "Alarm";
        rule.enabled = prefs.getBoolean(KEY_ALARM_ENABLED, true);
        rule.pattern = prefs.getString(KEY_ALARM_PATTERN, "warning");
        rule.strength = prefs.getInt(KEY_ALARM_STRENGTH, 85);
        rule.durationMs = prefs.getInt(KEY_ALARM_DURATION, 120);
        rule.color = prefs.getInt(KEY_ALARM_COLOR, 0xFF3030);
        rule.cooldownMs = prefs.getInt(KEY_ALARM_COOLDOWN, 900);
        rule.priority = 95;
        sanitize(rule);
        return rule;
    }

    static void saveAlarmRule(Context context, AppRule rule, boolean repeat) {
        sanitize(rule);
        prefs(context).edit()
                .putBoolean(KEY_ALARM_ENABLED, rule.enabled)
                .putBoolean(KEY_ALARM_REPEAT, repeat)
                .putString(KEY_ALARM_PATTERN, rule.pattern)
                .putInt(KEY_ALARM_STRENGTH, rule.strength)
                .putInt(KEY_ALARM_DURATION, rule.durationMs)
                .putInt(KEY_ALARM_COLOR, rule.color)
                .putInt(KEY_ALARM_COOLDOWN, rule.cooldownMs)
                .apply();
    }

    static AppRule preAlarmRule(Context context) {
        SharedPreferences prefs = prefs(context);
        AppRule rule = new AppRule();
        rule.packageName = "__pre_alarm__";
        rule.label = "Pre-alarm";
        rule.enabled = prefs.getBoolean(KEY_PRE_ALARM_ENABLED, true);
        rule.pattern = prefs.getString(KEY_PRE_ALARM_PATTERN, "long_tap");
        rule.strength = prefs.getInt(KEY_PRE_ALARM_STRENGTH, 70);
        rule.durationMs = prefs.getInt(KEY_PRE_ALARM_DURATION, 150);
        rule.color = prefs.getInt(KEY_PRE_ALARM_COLOR, 0xFFD33D);
        rule.cooldownMs = prefs.getInt(KEY_PRE_ALARM_COOLDOWN, 10 * 60_000);
        rule.priority = 90;
        sanitize(rule);
        return rule;
    }

    static void savePreAlarmRule(Context context, AppRule rule) {
        sanitize(rule);
        prefs(context).edit()
                .putBoolean(KEY_PRE_ALARM_ENABLED, rule.enabled)
                .putString(KEY_PRE_ALARM_PATTERN, rule.pattern)
                .putInt(KEY_PRE_ALARM_STRENGTH, rule.strength)
                .putInt(KEY_PRE_ALARM_DURATION, rule.durationMs)
                .putInt(KEY_PRE_ALARM_COLOR, rule.color)
                .putInt(KEY_PRE_ALARM_COOLDOWN, rule.cooldownMs)
                .apply();
    }

    static boolean isPattern(String pattern) {
        if (pattern == null) {
            return false;
        }
        for (String candidate : PATTERNS) {
            if (candidate.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    static String nextPattern(String current) {
        int index = 0;
        for (int i = 0; i < PATTERNS.length; i++) {
            if (PATTERNS[i].equals(current)) {
                index = i;
                break;
            }
        }
        return PATTERNS[(index + 1) % PATTERNS.length];
    }

    static int nextColor(int color) {
        int[] colors = {0x37B8FF, 0xFFFFFF, 0xFF3030, 0x36D675, 0xFFD33D, 0xB38CFF, 0xFF2D95};
        for (int i = 0; i < colors.length; i++) {
            if ((colors[i] & 0xFFFFFF) == (color & 0xFFFFFF)) {
                return colors[(i + 1) % colors.length];
            }
        }
        return colors[0];
    }

    static String colorHex(int color) {
        return String.format(Locale.US, "%06X", color & 0xFFFFFF);
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static void sanitize(AppRule rule) {
        if (rule.label == null || rule.label.trim().isEmpty()) {
            rule.label = rule.packageName;
        }
        if (!isPattern(rule.pattern)) {
            rule.pattern = "single";
        }
        rule.strength = clamp(rule.strength, 0, 100);
        rule.durationMs = clamp(rule.durationMs, 25, 2000);
        rule.cooldownMs = clamp(rule.cooldownMs, 0, 60 * 60_000);
        rule.priority = clamp(rule.priority, 0, 100);
        rule.color &= 0xFFFFFF;
    }
}

final class AppRule {
    String packageName;
    String label;
    boolean enabled = true;
    String pattern = "single";
    int strength = 55;
    int durationMs = 90;
    int color = 0x37B8FF;
    int cooldownMs = 2000;
    int priority = 70;
}
