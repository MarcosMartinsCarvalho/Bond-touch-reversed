package com.marco.bondcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

final class HapticSettingsStore {
    static final String[] PATTERNS = {
            "single", "double", "triple", "heartbeat", "long", "ramp", "rise", "fall", "wave", "stutter", "warning", "breathe", "none"
    };

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Long> LAST_EVENT_MS = new LinkedHashMap<>();
    private static final ArrayDeque<Long> RECENT_MS = new ArrayDeque<>();
    private static HapticSettings settings;

    private HapticSettingsStore() {
    }

    static HapticSettings get() {
        if (settings == null) {
            load();
        }
        return settings;
    }

    static void load() {
        Path path = path();
        HapticSettings defaults = defaults();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                settings = GSON.fromJson(reader, HapticSettings.class);
            } catch (Exception ex) {
                BondCraft.LOGGER.warn("Could not read BondCraft haptic settings, using defaults: {}", ex.getMessage());
                settings = defaults;
            }
        } else {
            settings = defaults;
        }
        if (settings == null) {
            settings = defaults;
        }
        mergeDefaults(settings, defaults);
        migrate(settings);
        sanitize(settings);
        save();
    }

    static void save() {
        try {
            Path path = path();
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(get(), writer);
            }
        } catch (IOException ex) {
            BondCraft.LOGGER.warn("Could not save BondCraft haptic settings: {}", ex.getMessage());
        }
    }

    static JsonObject payloadFor(String event, Map<String, Object> data, String playerName) {
        HapticSettings current = get();
        if (!current.enabled) {
            return null;
        }
        HapticRule rule = current.rules.get(event);
        if (rule == null || !rule.enabled) {
            return null;
        }
        long now = System.currentTimeMillis();
        while (!RECENT_MS.isEmpty() && now - RECENT_MS.peekFirst() > 10_000) {
            RECENT_MS.removeFirst();
        }
        if (RECENT_MS.size() >= current.burstLimitPerTenSeconds && rule.priority < 80) {
            return null;
        }
        int cooldown = Math.max(current.globalCooldownMs, rule.cooldownMs);
        long last = LAST_EVENT_MS.getOrDefault(event, -100_000L);
        if (now - last < cooldown) {
            return null;
        }
        LAST_EVENT_MS.put(event, now);
        RECENT_MS.addLast(now);

        int strength = clamp(Math.round((rule.strength + dynamicStrength(event, data)) * (current.globalScale / 100f)),
                current.minStrength, current.maxStrength);
        int duration = clamp(rule.durationMs + dynamicDuration(event, data), current.minDurationMs, current.maxDurationMs);

        JsonObject payload = new JsonObject();
        payload.addProperty("source", "bondcraft");
        payload.addProperty("final", true);
        payload.addProperty("event", event);
        payload.addProperty("player", playerName);
        payload.addProperty("pattern", rule.pattern);
        payload.addProperty("strength", strength);
        payload.addProperty("duration", duration);
        payload.addProperty("cooldown", cooldown);
        payload.addProperty("priority", rule.priority);
        payload.addProperty("color", current.colorsEnabled ? rule.color : "");
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Number number) {
                payload.addProperty(entry.getKey(), number);
            } else if (value instanceof Boolean bool) {
                payload.addProperty(entry.getKey(), bool);
            } else if (value != null) {
                payload.addProperty(entry.getKey(), String.valueOf(value));
            }
        }
        return payload;
    }

    static void resetDefaults() {
        settings = defaults();
        save();
    }

    static HapticSettings defaultSettingsCopy() {
        return defaults();
    }

    static boolean calmModeActive() {
        HapticSettings current = get();
        if (current.calmModeEnabled) {
            return true;
        }
        HapticRule falling = current.rules.get("falling");
        HapticRule step = current.rules.get("step");
        HapticRule miningStone = current.rules.get("mining_stone");
        return current.globalCooldownMs >= 90
                && falling != null && !falling.enabled && falling.cooldownMs >= 1400
                && step != null && !step.enabled && step.cooldownMs >= 600
                && miningStone != null && miningStone.cooldownMs >= 300;
    }

    static boolean toggleCalmMode() {
        boolean active = calmModeActive();
        if (active) {
            restoreNormalPreset();
            get().calmModeEnabled = false;
        } else {
            applyCalmPreset();
            get().calmModeEnabled = true;
        }
        save();
        return !active;
    }

    static void applyCalmPreset() {
        HapticSettings current = get();
        current.globalCooldownMs = Math.max(current.globalCooldownMs, 90);
        current.burstLimitPerTenSeconds = Math.min(current.burstLimitPerTenSeconds, 22);
        quietRule(current, "falling", false, 48, 80, 1500);
        quietRule(current, "jump", true, 28, 45, 350);
        quietRule(current, "land", true, 72, 120, 450);
        for (String key : current.rules.keySet()) {
            if (key.startsWith("step")) {
                quietRule(current, key, false, Math.min(current.rules.get(key).strength, 18), 24, 650);
            } else if (key.startsWith("mining")) {
                quietRule(current, key, current.rules.get(key).enabled, Math.min(current.rules.get(key).strength, 26), 30, 360);
            }
        }
        current.calmModeEnabled = true;
        save();
    }

    static void restoreNormalPreset() {
        HapticSettings current = get();
        HapticSettings defaults = defaults();
        current.globalCooldownMs = defaults.globalCooldownMs;
        current.burstLimitPerTenSeconds = defaults.burstLimitPerTenSeconds;
        restoreRuleGroup(current, defaults, "falling", "jump", "land");
        for (String key : defaults.rules.keySet()) {
            if (key.startsWith("step") || key.startsWith("mining")) {
                restoreRuleGroup(current, defaults, key);
            }
        }
        current.calmModeEnabled = false;
        save();
    }

    static boolean importPreset() {
        Path importPath = importPath();
        if (!Files.exists(importPath)) {
            return false;
        }
        HapticSettings defaults = defaults();
        try (Reader reader = Files.newBufferedReader(importPath)) {
            settings = GSON.fromJson(reader, HapticSettings.class);
            if (settings == null) {
                settings = defaults;
            }
            mergeDefaults(settings, defaults);
            sanitize(settings);
            save();
            return true;
        } catch (Exception ex) {
            BondCraft.LOGGER.warn("Could not import BondCraft haptic preset: {}", ex.getMessage());
            return false;
        }
    }

    static boolean exportPreset() {
        save();
        try {
            Files.createDirectories(exportPath().getParent());
            Files.copy(path(), exportPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException ex) {
            BondCraft.LOGGER.warn("Could not export BondCraft haptic preset: {}", ex.getMessage());
            return false;
        }
    }

    private static int dynamicStrength(String event, Map<String, Object> data) {
        return switch (event) {
            case "damage" -> (int) Math.round(number(data, "amount") * 4);
            case "land" -> (int) Math.round(Math.max(0, number(data, "drop") - 2) * 7);
            case "creeper_warning", "creeper_close" -> (int) Math.round(Math.max(0, 8 - number(data, "distance")) * 6);
            case "hostile_nearby" -> (int) Math.min(35, number(data, "count") * 5);
            case "falling" -> (int) Math.round(Math.abs(number(data, "speedY")) * 18);
            case "bow_shot" -> (int) Math.min(25, number(data, "chargeTicks") / 2);
            case "cobblemon_damage", "cobblemon_type_fire", "cobblemon_type_water", "cobblemon_type_wind",
                    "cobblemon_type_electric", "cobblemon_type_ice", "cobblemon_type_ground",
                    "cobblemon_type_rock", "cobblemon_type_grass", "cobblemon_type_psychic",
                    "cobblemon_type_ghost", "cobblemon_type_dark", "cobblemon_type_fairy",
                    "cobblemon_type_dragon", "cobblemon_type_poison", "cobblemon_type_steel",
                    "cobblemon_type_bug", "cobblemon_type_fighting", "cobblemon_type_normal" ->
                    (int) Math.min(35, Math.round(number(data, "percent") / 2.5)
                            + (bool(data, "crit") ? 18 : 0)
                            + ("super".equals(string(data, "effectiveness")) ? 14 : 0)
                            - ("resisted".equals(string(data, "effectiveness")) ? 10 : 0));
            case "cobblemon_crit" -> 18;
            case "cobblemon_super_effective" -> 16;
            default -> 0;
        };
    }

    private static int dynamicDuration(String event, Map<String, Object> data) {
        return switch (event) {
            case "damage" -> (int) Math.round(number(data, "amount") * 10);
            case "land" -> (int) Math.round(Math.max(0, number(data, "drop") - 2) * 18);
            case "cobblemon_damage", "cobblemon_type_fire", "cobblemon_type_water", "cobblemon_type_wind",
                    "cobblemon_type_electric", "cobblemon_type_ice", "cobblemon_type_ground",
                    "cobblemon_type_rock", "cobblemon_type_grass", "cobblemon_type_psychic",
                    "cobblemon_type_ghost", "cobblemon_type_dark", "cobblemon_type_fairy",
                    "cobblemon_type_dragon", "cobblemon_type_poison", "cobblemon_type_steel",
                    "cobblemon_type_bug", "cobblemon_type_fighting", "cobblemon_type_normal" ->
                    (int) Math.min(80, Math.round(number(data, "percent") / 2.0) + (bool(data, "crit") ? 35 : 0));
            default -> 0;
        };
    }

    private static double number(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean bool(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value instanceof Boolean bool && bool;
    }

    private static String string(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static HapticSettings defaults() {
        HapticSettings settings = new HapticSettings();
        put(settings, "session_start", "Join World", true, "single", 40, 90, 2200, "#FFFFFF", 40);
        put(settings, "damage", "Damage", true, "single", 82, 135, 180, "#FF3030", 90);
        put(settings, "heal", "Heal", true, "single", 38, 90, 450, "#36D675", 45);
        put(settings, "low_health", "Low Health", true, "heartbeat", 72, 120, 1600, "#FF2020", 85);
        put(settings, "death", "Death", true, "long", 100, 900, 2500, "#660000", 100);
        put(settings, "respawn", "Respawn", true, "double", 55, 120, 900, "#FFFFFF", 75);
        put(settings, "hunger_low", "Hungry", true, "double", 44, 95, 2400, "#FFD33D", 50);
        put(settings, "food_restored", "Food Restored", false, "single", 35, 80, 800, "#FFD33D", 20);
        put(settings, "air_low", "Low Air", true, "warning", 74, 95, 550, "#37B8FF", 80);
        put(settings, "xp_gain", "XP Gain", false, "single", 26, 45, 350, "#7DFF4D", 25);
        put(settings, "level_up", "Level Up", true, "triple", 64, 100, 1100, "#7DFF4D", 70);
        put(settings, "step", "Generic Step", false, "single", 12, 22, 650, "", 8);
        put(settings, "sprint_step", "Sprint Step", false, "single", 16, 24, 520, "", 10);
        put(settings, "step_stone", "Step On Stone", false, "single", 15, 24, 650, "", 10);
        put(settings, "step_wood", "Step On Wood", false, "single", 14, 26, 650, "#A86A32", 10);
        put(settings, "step_grass", "Step On Grass", false, "single", 10, 22, 700, "#48B84A", 8);
        put(settings, "step_sand", "Step On Sand", false, "fall", 12, 26, 700, "#E2C26D", 8);
        put(settings, "step_gravel", "Step On Gravel", false, "stutter", 14, 24, 700, "#8E8E8E", 9);
        put(settings, "step_snow", "Step On Snow", false, "single", 10, 28, 700, "#DCEEFF", 8);
        put(settings, "step_metal", "Step On Metal", false, "single", 22, 34, 650, "#C0C6CC", 12);
        put(settings, "jump", "Jump", true, "rise", 28, 45, 350, "", 25);
        put(settings, "falling", "Real Fall Warning", false, "rise", 48, 80, 1500, "#B38CFF", 45);
        put(settings, "land", "Hard Landing", true, "fall", 72, 120, 450, "#C48A4A", 85);
        put(settings, "crouch_start", "Crouch", false, "single", 22, 45, 150, "", 15);
        put(settings, "crouch_stop", "Uncrouch", false, "single", 18, 35, 150, "", 12);
        put(settings, "sprint_start", "Sprint Start", false, "rise", 34, 55, 250, "", 18);
        put(settings, "sprint_stop", "Sprint Stop", false, "fall", 26, 45, 250, "", 16);
        put(settings, "swim_start", "Swim Start", true, "wave", 34, 65, 450, "#37B8FF", 30);
        put(settings, "swim_stop", "Swim Stop", false, "single", 24, 45, 450, "#37B8FF", 20);
        put(settings, "water_enter", "Enter Water", true, "wave", 42, 85, 600, "#37B8FF", 45);
        put(settings, "lava_enter", "Enter Lava", true, "triple", 100, 135, 300, "#FF6B00", 100);
        put(settings, "fire", "On Fire", true, "warning", 88, 90, 500, "#FF4500", 90);
        put(settings, "sleep", "Sleep", true, "breathe", 28, 110, 1200, "#776CFF", 30);
        put(settings, "dimension_change", "Dimension Change", true, "long", 72, 180, 1500, "#B38CFF", 75);
        put(settings, "weather_rain", "Rain Starts", true, "wave", 26, 55, 2800, "#37B8FF", 25);
        put(settings, "rain_ground", "Rain On Ground", false, "stutter", 18, 26, 900, "#37B8FF", 12);
        put(settings, "weather_thunder", "Thunder Starts", true, "long", 86, 170, 2800, "#FFFFFF", 70);
        put(settings, "thunder_rumble", "Thunder Rumble", true, "fall", 62, 190, 4200, "#FFFFFF", 55);
        put(settings, "night", "Night Starts", true, "breathe", 34, 100, 3200, "#776CFF", 30);
        put(settings, "mine_start", "Start Mining", true, "single", 26, 40, 280, "", 25);
        put(settings, "mining", "Generic Mining", false, "single", 18, 30, 380, "", 10);
        put(settings, "mining_stone", "Mining Stone", true, "stutter", 22, 30, 360, "#9A9A9A", 20);
        put(settings, "mining_wood", "Mining Wood", true, "fall", 20, 42, 360, "#A86A32", 20);
        put(settings, "mining_ore", "Mining Ore", true, "stutter", 34, 40, 260, "#7DFF4D", 35);
        put(settings, "mining_dirt", "Mining Dirt", false, "single", 18, 36, 220, "#8A5A32", 12);
        put(settings, "mining_sand", "Mining Sand", false, "fall", 16, 38, 240, "#E2C26D", 12);
        put(settings, "mining_gravel", "Mining Gravel", false, "stutter", 20, 34, 240, "#8E8E8E", 12);
        put(settings, "attack_swing", "Attack Swing", true, "single", 54, 70, 150, "#FF3030", 55);
        put(settings, "bow_charge", "Bow Charge", false, "rise", 32, 70, 550, "#FFD33D", 25);
        put(settings, "bow_shot", "Bow Shot", true, "fall", 68, 95, 140, "#FFD33D", 65);
        put(settings, "fishing_cast", "Fishing Cast", true, "rise", 40, 85, 650, "#37B8FF", 35);
        put(settings, "blocking", "Shield Up", true, "single", 52, 80, 420, "#FFFFFF", 45);
        put(settings, "eating", "Finished Eating", true, "double", 34, 75, 420, "#FFD33D", 25);
        put(settings, "drinking", "Finished Drinking", true, "double", 38, 75, 420, "#37B8FF", 25);
        put(settings, "creeper_warning", "Creeper Nearby", true, "warning", 66, 100, 700, "#4DFF4D", 75);
        put(settings, "creeper_close", "Creeper Close", true, "triple", 100, 105, 280, "#55FF55", 100);
        put(settings, "hostile_nearby", "Hostile Nearby", true, "double", 54, 90, 1300, "#FF3030", 60);
        put(settings, "effect_added", "Effect Added", true, "single", 36, 70, 420, "#B38CFF", 25);
        put(settings, "effect_removed", "Effect Removed", false, "single", 26, 60, 420, "#B38CFF", 15);
        put(settings, "elytra_start", "Elytra Start", true, "rise", 48, 90, 850, "#5DDCFF", 45);
        put(settings, "elytra_stop", "Elytra Stop", true, "fall", 38, 90, 850, "#5DDCFF", 35);
        put(settings, "mount", "Mount", true, "single", 35, 75, 600, "", 25);
        put(settings, "dismount", "Dismount", true, "single", 32, 70, 600, "", 25);
        put(settings, "hotbar_change", "Hotbar Change", false, "single", 12, 25, 80, "", 8);
        put(settings, "cobblemon_battle_start", "Cobblemon Battle Start", true, "double", 42, 90, 1800, "#FFFFFF", 45);
        put(settings, "cobblemon_ball_throw", "Cobblemon Ball Throw", true, "rise", 44, 85, 650, "#FF3030", 55);
        put(settings, "cobblemon_move", "Cobblemon Move Used", false, "single", 22, 50, 650, "#FFFFFF", 12);
        put(settings, "cobblemon_damage", "Cobblemon Damage", true, "single", 42, 80, 650, "#FF3030", 50);
        put(settings, "cobblemon_crit", "Cobblemon Critical Hit", true, "triple", 68, 90, 1000, "#FFD33D", 80);
        put(settings, "cobblemon_super_effective", "Cobblemon Super Effective", true, "rise", 58, 85, 900, "#7DFF4D", 75);
        put(settings, "cobblemon_not_effective", "Cobblemon Not Effective", true, "fall", 30, 70, 900, "#8E8E8E", 35);
        put(settings, "cobblemon_no_effect", "Cobblemon No Effect", true, "stutter", 24, 45, 1100, "#8E8E8E", 35);
        put(settings, "cobblemon_faint", "Cobblemon Faint", true, "long", 70, 350, 1800, "#B38CFF", 80);
        put(settings, "cobblemon_type_fire", "Cobblemon Fire Hit", true, "warning", 54, 80, 750, "#FF4500", 60);
        put(settings, "cobblemon_type_water", "Cobblemon Water Hit", true, "wave", 46, 90, 750, "#37B8FF", 55);
        put(settings, "cobblemon_type_wind", "Cobblemon Wind/Flying Hit", true, "wave", 38, 80, 750, "#DCEEFF", 45);
        put(settings, "cobblemon_type_electric", "Cobblemon Electric Hit", true, "stutter", 60, 55, 750, "#FFD33D", 65);
        put(settings, "cobblemon_type_ice", "Cobblemon Ice Hit", true, "fall", 42, 95, 750, "#5DDCFF", 50);
        put(settings, "cobblemon_type_ground", "Cobblemon Ground Hit", true, "fall", 50, 110, 800, "#C48A4A", 55);
        put(settings, "cobblemon_type_rock", "Cobblemon Rock Hit", true, "single", 56, 100, 800, "#9A9A9A", 55);
        put(settings, "cobblemon_type_grass", "Cobblemon Grass Hit", true, "rise", 34, 75, 800, "#36D675", 40);
        put(settings, "cobblemon_type_psychic", "Cobblemon Psychic Hit", true, "breathe", 36, 85, 950, "#B38CFF", 45);
        put(settings, "cobblemon_type_ghost", "Cobblemon Ghost Hit", true, "breathe", 36, 90, 950, "#776CFF", 45);
        put(settings, "cobblemon_type_dark", "Cobblemon Dark Hit", true, "long", 38, 120, 950, "#660000", 45);
        put(settings, "cobblemon_type_fairy", "Cobblemon Fairy Hit", true, "wave", 34, 80, 900, "#FF6BCE", 40);
        put(settings, "cobblemon_type_dragon", "Cobblemon Dragon Hit", true, "ramp", 58, 90, 950, "#B38CFF", 65);
        put(settings, "cobblemon_type_poison", "Cobblemon Poison Hit", true, "stutter", 40, 70, 900, "#A04DFF", 45);
        put(settings, "cobblemon_type_steel", "Cobblemon Steel Hit", true, "single", 58, 85, 800, "#C0C6CC", 55);
        put(settings, "cobblemon_type_bug", "Cobblemon Bug Hit", true, "stutter", 32, 50, 850, "#7DFF4D", 35);
        put(settings, "cobblemon_type_fighting", "Cobblemon Fighting Hit", true, "double", 54, 75, 750, "#FF3030", 55);
        put(settings, "cobblemon_type_normal", "Cobblemon Normal Hit", false, "single", 30, 65, 750, "#FFFFFF", 25);
        return settings;
    }

    private static void migrate(HapticSettings settings) {
        if (settings.schemaVersion >= 2) {
            return;
        }
        applyCalmMigration(settings);
        settings.schemaVersion = 2;
    }

    private static void applyCalmMigration(HapticSettings settings) {
        settings.globalCooldownMs = Math.max(settings.globalCooldownMs, 80);
        settings.burstLimitPerTenSeconds = Math.min(settings.burstLimitPerTenSeconds, 24);
        quietRule(settings, "falling", false, 48, 80, 1500);
        quietRule(settings, "jump", true, 28, 45, 350);
        quietRule(settings, "land", true, 72, 120, 450);
        for (String key : settings.rules.keySet()) {
            HapticRule rule = settings.rules.get(key);
            if (key.startsWith("step")) {
                quietRule(settings, key, false, Math.min(rule.strength, 18), Math.min(rule.durationMs, 28), Math.max(rule.cooldownMs, 650));
            } else if (key.startsWith("mining")) {
                quietRule(settings, key, rule.enabled, Math.min(rule.strength, 28), Math.min(rule.durationMs, 42), Math.max(rule.cooldownMs, 300));
            }
        }
    }

    private static void quietRule(HapticSettings settings, String key, boolean enabled, int strength, int durationMs, int cooldownMs) {
        HapticRule rule = settings.rules.get(key);
        if (rule == null) {
            return;
        }
        rule.enabled = enabled;
        rule.strength = strength;
        rule.durationMs = durationMs;
        rule.cooldownMs = cooldownMs;
    }

    private static void restoreRuleGroup(HapticSettings settings, HapticSettings defaults, String... keys) {
        for (String key : keys) {
            HapticRule defaultRule = defaults.rules.get(key);
            if (defaultRule != null) {
                settings.rules.put(key, defaultRule.copy());
            }
        }
    }

    private static void put(HapticSettings settings, String key, String label, boolean enabled, String pattern,
                            int strength, int durationMs, int cooldownMs, String color, int priority) {
        settings.rules.put(key, new HapticRule(label, enabled, pattern, strength, durationMs, cooldownMs, color, priority));
    }

    private static void mergeDefaults(HapticSettings settings, HapticSettings defaults) {
        if (settings.rules == null) {
            settings.rules = new LinkedHashMap<>();
        }
        for (Map.Entry<String, HapticRule> entry : defaults.rules.entrySet()) {
            settings.rules.putIfAbsent(entry.getKey(), entry.getValue().copy());
        }
    }

    private static void sanitize(HapticSettings settings) {
        settings.minStrength = clamp(settings.minStrength, 0, 100);
        settings.maxStrength = clamp(settings.maxStrength, settings.minStrength, 100);
        settings.minDurationMs = clamp(settings.minDurationMs, 10, 5000);
        settings.maxDurationMs = clamp(settings.maxDurationMs, settings.minDurationMs, 10_000);
        settings.globalScale = clamp(settings.globalScale, 10, 200);
        settings.globalCooldownMs = clamp(settings.globalCooldownMs, 0, 2000);
        settings.burstLimitPerTenSeconds = clamp(settings.burstLimitPerTenSeconds, 4, 80);
        for (HapticRule rule : settings.rules.values()) {
            rule.strength = clamp(rule.strength, 0, 100);
            rule.durationMs = clamp(rule.durationMs, 10, 5000);
            rule.cooldownMs = clamp(rule.cooldownMs, 0, 10_000);
            rule.priority = clamp(rule.priority, 0, 100);
            if (rule.pattern == null || rule.pattern.isBlank()) {
                rule.pattern = "single";
            }
            if (rule.color == null) {
                rule.color = "";
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Path path() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("bondcraft-haptics.json");
    }

    private static Path importPath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("bondcraft-haptics-import.json");
    }

    private static Path exportPath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("bondcraft-haptics-export.json");
    }
}
