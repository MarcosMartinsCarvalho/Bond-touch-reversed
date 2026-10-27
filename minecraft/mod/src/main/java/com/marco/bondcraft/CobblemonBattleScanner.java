package com.marco.bondcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CobblemonBattleScanner {
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d{1,3})\\s*%");
    private static final Pattern DAMAGE_PATTERN = Pattern.compile("(?:damage|lost|hit for|took)\\D{0,12}(\\d{1,3})");

    private final BridgeClient bridge;
    private final Map<String, Long> lastEventTicks = new LinkedHashMap<>();
    private Set<String> lastTexts = Set.of();
    private boolean inBattleScreen;
    private boolean cobblemonChecked;
    private boolean cobblemonLoaded;

    CobblemonBattleScanner(BridgeClient bridge) {
        this.bridge = bridge;
    }

    void tick(Minecraft minecraft, LocalPlayer player, ClientLevel level, long tick) {
        if (!isCobblemonLoaded()) {
            return;
        }
        Screen screen = minecraft.screen;
        boolean battleScreen = isBattleScreen(screen);
        if (battleScreen && !inBattleScreen) {
            inBattleScreen = true;
            lastTexts = Set.of();
            emit("cobblemon_battle_start", 80, Map.of("screen", screen.getClass().getName()), tick);
        } else if (!battleScreen) {
            inBattleScreen = false;
            lastTexts = Set.of();
            return;
        }

        if (tick % 8 != 0) {
            return;
        }
        List<String> texts = new ArrayList<>();
        collectText(screen, texts, newSetFromIdentity(), 0);
        Set<String> current = normalize(texts);
        if (current.isEmpty()) {
            return;
        }
        List<String> fresh = current.stream()
                .filter(text -> !lastTexts.contains(text))
                .toList();
        lastTexts = current;
        if (fresh.isEmpty()) {
            return;
        }
        analyzeFreshText(fresh, tick);
    }

    private boolean isCobblemonLoaded() {
        if (!cobblemonChecked) {
            cobblemonChecked = true;
            cobblemonLoaded = ModList.get().isLoaded("cobblemon");
        }
        return cobblemonLoaded;
    }

    private boolean isBattleScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String className = screen.getClass().getName().toLowerCase(Locale.ROOT);
        return className.contains("cobblemon") && className.contains("battle");
    }

    private void analyzeFreshText(List<String> fresh, long tick) {
        String combined = String.join(" | ", fresh).toLowerCase(Locale.ROOT);
        if (!looksBattleRelevant(combined)) {
            return;
        }

        String type = typeFromText(combined);
        String effectiveness = effectiveness(combined);
        boolean crit = combined.contains("critical");
        int percent = damagePercent(combined);
        String event;
        if (isBallThrowText(combined)) {
            event = "cobblemon_ball_throw";
        } else if (combined.contains("fainted")) {
            event = "cobblemon_faint";
        } else if (crit) {
            event = "cobblemon_crit";
        } else if (type != null && (combined.contains("damage") || combined.contains("hit") || combined.contains("used") || percent > 0)) {
            event = "cobblemon_type_" + type;
        } else if ("super".equals(effectiveness)) {
            event = "cobblemon_super_effective";
        } else if ("none".equals(effectiveness)) {
            event = "cobblemon_no_effect";
        } else if ("resisted".equals(effectiveness)) {
            event = "cobblemon_not_effective";
        } else if (percent > 0 || combined.contains("damage") || combined.contains("hit")) {
            event = "cobblemon_damage";
        } else if (combined.contains("used")) {
            event = "cobblemon_move";
        } else {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", trim(combined, 180));
        payload.put("type", type == null ? "" : type);
        payload.put("effectiveness", effectiveness);
        payload.put("crit", crit);
        payload.put("percent", percent);
        emit(event, eventCooldown(event), payload, tick);
    }

    private boolean looksBattleRelevant(String text) {
        return text.contains("battle") || text.contains("used") || text.contains("fainted")
                || text.contains("effective") || text.contains("critical") || text.contains("damage")
                || text.contains("hit") || text.contains("attack") || typeFromText(text) != null;
    }

    private String effectiveness(String text) {
        if (text.contains("super effective")) {
            return "super";
        }
        if (text.contains("not very effective")) {
            return "resisted";
        }
        if (text.contains("doesn't affect") || text.contains("does not affect") || text.contains("no effect")) {
            return "none";
        }
        return "";
    }

    private int damagePercent(String text) {
        Matcher percent = PERCENT_PATTERN.matcher(text);
        int best = 0;
        while (percent.find()) {
            best = Math.max(best, clamp(parse(percent.group(1)), 0, 100));
        }
        if (best > 0) {
            return best;
        }
        Matcher damage = DAMAGE_PATTERN.matcher(text);
        while (damage.find()) {
            best = Math.max(best, clamp(parse(damage.group(1)), 0, 100));
        }
        return best;
    }

    private String typeFromText(String text) {
        if (containsAny(text, "fire", "flame", "burn", "blaze", "ember", "lava")) {
            return "fire";
        }
        if (containsAny(text, "water", "aqua", "hydro", "surf", "bubble", "rain")) {
            return "water";
        }
        if (containsAny(text, "flying", "wind", "air", "gust", "hurricane", "tornado", "aerial")) {
            return "wind";
        }
        if (containsAny(text, "electric", "thunder", "shock", "volt", "zap", "spark")) {
            return "electric";
        }
        if (containsAny(text, "ice", "frost", "freeze", "blizzard", "snow")) {
            return "ice";
        }
        if (containsAny(text, "ground", "earth", "quake", "mud", "sand")) {
            return "ground";
        }
        if (containsAny(text, "rock", "stone", "boulder")) {
            return "rock";
        }
        if (containsAny(text, "grass", "leaf", "seed", "vine", "petal")) {
            return "grass";
        }
        if (containsAny(text, "psychic", "psybeam", "mind", "confusion")) {
            return "psychic";
        }
        if (containsAny(text, "ghost", "shadow", "phantom", "spirit")) {
            return "ghost";
        }
        if (containsAny(text, "dark", "night", "bite", "crunch")) {
            return "dark";
        }
        if (containsAny(text, "fairy", "moon", "pixie", "gleam")) {
            return "fairy";
        }
        if (containsAny(text, "dragon", "draco", "wyrm")) {
            return "dragon";
        }
        if (containsAny(text, "poison", "toxic", "venom", "sludge")) {
            return "poison";
        }
        if (containsAny(text, "steel", "iron", "metal", "gear")) {
            return "steel";
        }
        if (containsAny(text, "bug", "web", "sting", "swarm")) {
            return "bug";
        }
        if (containsAny(text, "fighting", "punch", "kick", "combat", "karate", "aura", "focus",
                "close combat", "brick break", "mach", "revenge", "counter", "force palm",
                "low kick", "low sweep", "high jump", "drain punch", "dynamic punch", "power-up",
                "sacred sword", "superpower", "submission", "vacuum wave", "circle throw",
                "hammer arm", "double kick", "rolling kick", "seismic toss")) {
            return "fighting";
        }
        if (containsAny(text, "normal", "tackle", "slam", "scratch")) {
            return "normal";
        }
        return null;
    }

    private int eventCooldown(String event) {
        return switch (event) {
            case "cobblemon_ball_throw" -> 18;
            case "cobblemon_move" -> 30;
            case "cobblemon_damage" -> 22;
            case "cobblemon_type_normal" -> 28;
            case "cobblemon_crit", "cobblemon_super_effective", "cobblemon_faint" -> 36;
            default -> 26;
        };
    }

    private void emit(String event, int cooldownTicks, Map<String, Object> extra, long tick) {
        long last = lastEventTicks.getOrDefault(event, -10_000L);
        if (tick - last < cooldownTicks) {
            return;
        }
        lastEventTicks.put(event, tick);
        bridge.send(event, extra);
    }

    private boolean isBallThrowText(String text) {
        return containsAny(text, "poke ball", "great ball", "ultra ball", "premier ball", "heal ball",
                "net ball", "nest ball", "dive ball", "dusk ball", "quick ball", "timer ball",
                "luxury ball", "level ball", "lure ball", "moon ball", "friend ball", "love ball",
                "heavy ball", "fast ball", "sport ball", "safari ball", "master ball")
                || (text.contains("ball") && containsAny(text, "throw", "threw", "used", "capture",
                "catch", "caught", "broke free", "escaped"));
    }

    private Set<String> normalize(List<String> texts) {
        Set<String> normalized = new HashSet<>();
        for (String text : texts) {
            String clean = text.replace('\n', ' ').replace('\r', ' ').trim();
            clean = clean.replaceAll("\\s+", " ");
            if (clean.length() >= 3 && clean.length() <= 220) {
                normalized.add(clean);
            }
        }
        return normalized;
    }

    private void collectText(Object value, List<String> out, Set<Object> seen, int depth) {
        if (value == null || out.size() >= 80 || depth > 4) {
            return;
        }
        Class<?> type = value.getClass();
        if (type.isPrimitive() || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return;
        }
        if (value instanceof Component component) {
            addText(out, component.getString());
            return;
        }
        if (value instanceof CharSequence sequence) {
            addText(out, sequence.toString());
            return;
        }
        if (!seen.add(value)) {
            return;
        }
        if (type.isArray()) {
            int length = Math.min(Array.getLength(value), 40);
            for (int i = 0; i < length; i++) {
                collectText(Array.get(value, i), out, seen, depth + 1);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object item : iterable) {
                collectText(item, out, seen, depth + 1);
                if (++count >= 40) {
                    break;
                }
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                collectText(entry.getKey(), out, seen, depth + 1);
                collectText(entry.getValue(), out, seen, depth + 1);
                if (++count >= 30) {
                    break;
                }
            }
            return;
        }
        String packageName = type.getPackageName();
        if (packageName.startsWith("java.") || packageName.startsWith("javax.") || packageName.startsWith("sun.")
                || packageName.startsWith("org.lwjgl")) {
            return;
        }
        for (Class<?> current = type; current != null && current != Object.class && out.size() < 80; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    if (!field.trySetAccessible()) {
                        continue;
                    }
                    collectText(field.get(value), out, seen, depth + 1);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void addText(List<String> out, String text) {
        if (text != null && !text.isBlank()) {
            out.add(text);
        }
    }

    private Set<Object> newSetFromIdentity() {
        return java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private int parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
