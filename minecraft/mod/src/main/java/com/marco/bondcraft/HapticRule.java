package com.marco.bondcraft;

final class HapticRule {
    boolean enabled;
    String label;
    String pattern;
    int strength;
    int durationMs;
    int cooldownMs;
    String color;
    int priority;

    HapticRule() {
    }

    HapticRule(String label, boolean enabled, String pattern, int strength, int durationMs,
               int cooldownMs, String color, int priority) {
        this.label = label;
        this.enabled = enabled;
        this.pattern = pattern;
        this.strength = strength;
        this.durationMs = durationMs;
        this.cooldownMs = cooldownMs;
        this.color = color;
        this.priority = priority;
    }

    HapticRule copy() {
        return new HapticRule(label, enabled, pattern, strength, durationMs, cooldownMs, color, priority);
    }
}
