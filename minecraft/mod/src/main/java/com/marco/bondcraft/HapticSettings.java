package com.marco.bondcraft;

import java.util.LinkedHashMap;
import java.util.Map;

final class HapticSettings {
    int schemaVersion = 2;
    int minStrength = 15;
    int maxStrength = 100;
    int minDurationMs = 25;
    int maxDurationMs = 1200;
    int globalScale = 100;
    int globalCooldownMs = 80;
    int burstLimitPerTenSeconds = 22;
    boolean colorsEnabled = true;
    boolean enabled = true;
    boolean calmModeEnabled = false;
    Map<String, HapticRule> rules = new LinkedHashMap<>();
}
