package com.marco.bondcraft;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class HapticSettingsScreen extends Screen {
    private static final String[] CATEGORIES = {
            "Core", "Move", "World", "Tools", "Threats", "Cobblemon", "All"
    };
    private static final String[] COLORS = {
            "", "#FFFFFF", "#FF3030", "#FF8A00", "#FFD33D", "#36D675", "#37B8FF",
            "#5DDCFF", "#B38CFF", "#FF6BCE", "#8E8E8E"
    };

    private final Screen parent;
    private int page;
    private int categoryIndex;
    private String status = "";

    HapticSettingsScreen(Screen parent) {
        super(Component.literal("BondCraft Haptics"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        HapticSettings settings = HapticSettingsStore.get();
        Layout layout = layout();
        int y = layout.top() + 22;

        addButtonFlow(layout.left(), y, layout.right(),
                spec("Enabled: " + onOff(settings.enabled), 88, () -> {
                    settings.enabled = !settings.enabled;
                    saveAndRebuild();
                }),
                spec(HapticSettingsStore.calmModeActive() ? "Normal" : "Calm", 66, () -> {
                    boolean calmEnabled = HapticSettingsStore.toggleCalmMode();
                    status = calmEnabled
                            ? "Calm mode enabled for noisy movement/mining rules"
                            : "Calm mode disabled; movement/mining rules restored";
                    rebuild();
                }),
                spec("Scale -", 66, () -> {
                    settings.globalScale = clamp(settings.globalScale - 10, 10, 200);
                    saveAndRebuild();
                }),
                spec("Scale " + settings.globalScale + "%", 86, () -> {
                    settings.globalScale = 100;
                    saveAndRebuild();
                }),
                spec("Scale +", 66, () -> {
                    settings.globalScale = clamp(settings.globalScale + 10, 10, 200);
                    saveAndRebuild();
                }),
                spec("Min -", 52, () -> {
                    settings.minStrength = clamp(settings.minStrength - 5, 0, settings.maxStrength);
                    saveAndRebuild();
                }),
                spec("Min " + settings.minStrength, 62, () -> {
                    settings.minStrength = 15;
                    saveAndRebuild();
                }),
                spec("Min +", 52, () -> {
                    settings.minStrength = clamp(settings.minStrength + 5, 0, settings.maxStrength);
                    saveAndRebuild();
                }),
                spec("Max -", 52, () -> {
                    settings.maxStrength = clamp(settings.maxStrength - 5, settings.minStrength, 100);
                    saveAndRebuild();
                }),
                spec("Max " + settings.maxStrength, 64, () -> {
                    settings.maxStrength = 100;
                    saveAndRebuild();
                }),
                spec("Max +", 52, () -> {
                    settings.maxStrength = clamp(settings.maxStrength + 5, settings.minStrength, 100);
                    saveAndRebuild();
                }),
                spec("Colors: " + onOff(settings.colorsEnabled), 84, () -> {
                    settings.colorsEnabled = !settings.colorsEnabled;
                    saveAndRebuild();
                })
        );

        y += 26;
        addButtonFlow(layout.left(), y, layout.right(),
                spec("CD -", 54, () -> {
                    settings.globalCooldownMs = clamp(settings.globalCooldownMs - 25, 0, 2000);
                    saveAndRebuild();
                }),
                spec("CD " + settings.globalCooldownMs, 72, () -> {
                    settings.globalCooldownMs = 80;
                    saveAndRebuild();
                }),
                spec("CD +", 54, () -> {
                    settings.globalCooldownMs = clamp(settings.globalCooldownMs + 25, 0, 2000);
                    saveAndRebuild();
                }),
                spec("Burst -", 64, () -> {
                    settings.burstLimitPerTenSeconds = clamp(settings.burstLimitPerTenSeconds - 2, 4, 80);
                    saveAndRebuild();
                }),
                spec("Burst " + settings.burstLimitPerTenSeconds, 78, () -> {
                    settings.burstLimitPerTenSeconds = 22;
                    saveAndRebuild();
                }),
                spec("Burst +", 64, () -> {
                    settings.burstLimitPerTenSeconds = clamp(settings.burstLimitPerTenSeconds + 2, 4, 80);
                    saveAndRebuild();
                }),
                spec("Import", 68, () -> {
                    status = HapticSettingsStore.importPreset()
                            ? "Imported config\\bondcraft-haptics-import.json"
                            : "Put a preset at config\\bondcraft-haptics-import.json";
                    rebuild();
                }),
                spec("Export", 68, () -> {
                    status = HapticSettingsStore.exportPreset()
                            ? "Exported config\\bondcraft-haptics-export.json"
                            : "Export failed; check the game log";
                    rebuild();
                }),
                spec("Defaults", 78, () -> {
                    HapticSettingsStore.resetDefaults();
                    status = "Defaults restored";
                    rebuild();
                }),
                spec("Done", 64, this::onClose)
        );

        y += 30;
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int nextCategory = i;
            Button categoryButton = button(layout.left() + i * 86, y, 80, 20,
                    (i == categoryIndex ? "> " : "") + CATEGORIES[i],
                    b -> {
                        categoryIndex = nextCategory;
                        page = 0;
                        rebuild();
                    });
            addRenderableWidget(categoryButton);
        }

        List<Map.Entry<String, HapticRule>> rules = rules();
        int rows = rowsPerPage(layout);
        int maxPage = Math.max(0, (rules.size() - 1) / rows);
        page = clamp(page, 0, maxPage);
        int start = page * rows;
        int rowTop = listTop(layout);
        for (int i = 0; i < rows && start + i < rules.size(); i++) {
            Map.Entry<String, HapticRule> entry = rules.get(start + i);
            addRuleRow(entry.getKey(), entry.getValue(), layout, rowTop + i * rowHeight());
        }

        int footerY = height - 27;
        int center = width / 2;
        addRenderableWidget(button(center - 190, footerY, 72, 20, "< Page", b -> {
            page = Math.max(0, page - 1);
            rebuild();
        }));
        addRenderableWidget(button(center - 112, footerY, 110, 20, "Page " + (page + 1) + "/" + (maxPage + 1), b -> {
        }));
        addRenderableWidget(button(center + 4, footerY, 72, 20, "Page >", b -> {
            page = Math.min(maxPage, page + 1);
            rebuild();
        }));
        addRenderableWidget(button(center + 86, footerY, 104, 20, "Reset Page", b -> {
            resetVisibleRules(rules, start, rows);
            status = "Visible page reset to defaults";
            rebuild();
        }));
    }

    private void addRuleRow(String key, HapticRule rule, Layout layout, int y) {
        int labelWidth = layout.compact() ? 140 : 210;
        int x = layout.left() + labelWidth + 10;
        int gap = layout.compact() ? 3 : 5;
        int buttonY = y + 11;
        int small = layout.compact() ? 25 : 29;
        int medium = layout.compact() ? 48 : 54;
        int pattern = layout.compact() ? 72 : 90;
        int duration = layout.compact() ? 58 : 66;
        int cooldown = layout.compact() ? 62 : 78;

        addRenderableWidget(button(x, buttonY, 48, 20, onOff(rule.enabled), b -> {
            rule.enabled = !rule.enabled;
            saveAndRebuild();
        }));
        x += 48 + gap;
        addRenderableWidget(button(x, buttonY, pattern, 20, rule.pattern, b -> {
            rule.pattern = nextPattern(rule.pattern);
            saveAndRebuild();
        }));
        x += pattern + gap;
        addRenderableWidget(button(x, buttonY, small, 20, "-S", b -> {
            rule.strength = clamp(rule.strength - 5, 0, 100);
            saveAndRebuild();
        }));
        x += small + gap;
        addRenderableWidget(button(x, buttonY, medium, 20, "S " + rule.strength, b -> {
            rule.strength = defaultRule(key).strength;
            saveAndRebuild();
        }));
        x += medium + gap;
        addRenderableWidget(button(x, buttonY, small, 20, "+S", b -> {
            rule.strength = clamp(rule.strength + 5, 0, 100);
            saveAndRebuild();
        }));
        x += small + gap;
        addRenderableWidget(button(x, buttonY, small, 20, "-D", b -> {
            rule.durationMs = clamp(rule.durationMs - 10, 10, 5000);
            saveAndRebuild();
        }));
        x += small + gap;
        addRenderableWidget(button(x, buttonY, duration, 20, rule.durationMs + "ms", b -> {
            rule.durationMs = defaultRule(key).durationMs;
            saveAndRebuild();
        }));
        x += duration + gap;
        addRenderableWidget(button(x, buttonY, small, 20, "+D", b -> {
            rule.durationMs = clamp(rule.durationMs + 10, 10, 5000);
            saveAndRebuild();
        }));
        x += small + gap;
        addRenderableWidget(button(x, buttonY, cooldown, 20, "CD " + rule.cooldownMs, b -> {
            rule.cooldownMs = nextCooldown(rule.cooldownMs);
            saveAndRebuild();
        }));
        x += cooldown + gap;
        addRenderableWidget(button(x, buttonY, layout.compact() ? 44 : 52, 20, "P " + rule.priority, b -> {
            rule.priority = rule.priority >= 100 ? 0 : rule.priority + 10;
            saveAndRebuild();
        }));
        x += (layout.compact() ? 44 : 52) + gap;
        if (x + 62 <= layout.right()) {
            addRenderableWidget(button(x, buttonY, 62, 20, colorLabel(rule.color), b -> {
                rule.color = nextColor(rule.color);
                saveAndRebuild();
            }));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        Layout layout = layout();
        int panelColor = 0xD0101218;
        int lineColor = 0x803E4755;
        graphics.fill(layout.left() - 8, 8, layout.right() + 8, height - 8, panelColor);
        graphics.fill(layout.left() - 8, 78, layout.right() + 8, 79, lineColor);
        graphics.fill(layout.left() - 8, height - 34, layout.right() + 8, height - 33, lineColor);
        drawRowBackgrounds(graphics, layout);

        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 13, 0xFFFFFF);
        graphics.drawCenteredString(font,
                Component.literal("B opens this screen. Categories keep settings readable at every GUI scale.")
                        .withStyle(ChatFormatting.GRAY),
                width / 2, 25, 0xAEB6C2);

        drawHeaders(graphics, layout);
        drawRowText(graphics, layout);
        if (!status.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal(status).withStyle(ChatFormatting.YELLOW), width / 2, height - 43, 0xFFD36A);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        List<Map.Entry<String, HapticRule>> rules = rules();
        int rows = rowsPerPage(layout());
        int maxPage = Math.max(0, (rules.size() - 1) / rows);
        if (scrollY < 0) {
            page = Math.min(maxPage, page + 1);
            rebuild();
            return true;
        }
        if (scrollY > 0) {
            page = Math.max(0, page - 1);
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        HapticSettingsStore.save();
        Minecraft.getInstance().setScreen(parent);
    }

    private void drawHeaders(GuiGraphics graphics, Layout layout) {
        int y = listTop(layout) - 11;
        int labelWidth = layout.compact() ? 140 : 210;
        int x = layout.left() + labelWidth + 10;
        graphics.drawString(font, "Event", layout.left() + 4, y, 0xAEB6C2);
        graphics.drawString(font, "On", x + 12, y, 0xAEB6C2);
        graphics.drawString(font, "Pattern", x + 56, y, 0xAEB6C2);
        graphics.drawString(font, "Strength", x + 150, y, 0xAEB6C2);
        graphics.drawString(font, "Duration", x + 265, y, 0xAEB6C2);
        graphics.drawString(font, "Cooldown", x + 385, y, 0xAEB6C2);
    }

    private void drawRowBackgrounds(GuiGraphics graphics, Layout layout) {
        List<Map.Entry<String, HapticRule>> rules = rules();
        int rows = rowsPerPage(layout);
        int start = page * rows;
        int rowTop = listTop(layout);
        for (int i = 0; i < rows && start + i < rules.size(); i++) {
            int y = rowTop + i * rowHeight();
            int rowColor = i % 2 == 0 ? 0x301D2430 : 0x20252D3A;
            graphics.fill(layout.left(), y + 2, layout.right(), y + rowHeight() - 2, rowColor);
        }
    }

    private void drawRowText(GuiGraphics graphics, Layout layout) {
        List<Map.Entry<String, HapticRule>> rules = rules();
        int rows = rowsPerPage(layout);
        int start = page * rows;
        int rowTop = listTop(layout);
        int labelWidth = layout.compact() ? 140 : 210;
        for (int i = 0; i < rows && start + i < rules.size(); i++) {
            Map.Entry<String, HapticRule> entry = rules.get(start + i);
            HapticRule rule = entry.getValue();
            int y = rowTop + i * rowHeight();
            int textColor = rule.enabled ? 0xF1F6FF : 0x7E8794;
            graphics.drawString(font, fit(rule.label, labelWidth - 4), layout.left() + 4, y + 7, textColor);
            String detail = entry.getKey() + "  " + categoryFor(entry.getKey());
            if (rule.color != null && !rule.color.isBlank()) {
                detail += "  " + rule.color;
            }
            graphics.drawString(font, fit(detail, labelWidth - 4), layout.left() + 4, y + 18, 0x8792A2);
        }
    }

    private void addButtonFlow(int left, int y, int right, ButtonSpec... specs) {
        int x = left;
        for (ButtonSpec spec : specs) {
            if (x + spec.width() > right) {
                break;
            }
            addRenderableWidget(button(x, y, spec.width(), 20, spec.text(), b -> spec.action().run()));
            x += spec.width() + 6;
        }
    }

    private List<Map.Entry<String, HapticRule>> rules() {
        String category = CATEGORIES[categoryIndex];
        List<Map.Entry<String, HapticRule>> all = new ArrayList<>(HapticSettingsStore.get().rules.entrySet());
        if ("All".equals(category)) {
            return all;
        }
        return all.stream()
                .filter(entry -> category.equals(categoryFor(entry.getKey())))
                .toList();
    }

    private void resetVisibleRules(List<Map.Entry<String, HapticRule>> visible, int start, int rows) {
        HapticSettings defaults = HapticSettingsStore.defaultSettingsCopy();
        HapticSettings settings = HapticSettingsStore.get();
        for (int i = 0; i < rows && start + i < visible.size(); i++) {
            String key = visible.get(start + i).getKey();
            HapticRule defaultRule = defaults.rules.get(key);
            if (defaultRule != null) {
                settings.rules.put(key, defaultRule.copy());
            }
        }
        HapticSettingsStore.save();
    }

    private HapticRule defaultRule(String key) {
        HapticRule rule = HapticSettingsStore.defaultSettingsCopy().rules.get(key);
        return rule == null ? new HapticRule(key, true, "single", 50, 100, 500, "", 30) : rule;
    }

    private String categoryFor(String key) {
        if (key.startsWith("cobblemon_")) {
            return "Cobblemon";
        }
        if (key.startsWith("step") || key.contains("jump") || key.contains("fall")
                || key.contains("crouch") || key.contains("sprint") || key.contains("swim")
                || key.contains("water") || key.contains("lava") || key.contains("elytra")
                || key.contains("mount") || key.contains("hotbar")) {
            return "Move";
        }
        if (key.startsWith("weather") || key.contains("rain") || key.contains("thunder")
                || key.contains("night") || key.contains("sleep") || key.contains("dimension")) {
            return "World";
        }
        if (key.contains("mine") || key.contains("attack") || key.contains("bow")
                || key.contains("fishing") || key.contains("blocking") || key.contains("eating")
                || key.contains("drinking")) {
            return "Tools";
        }
        if (key.contains("creeper") || key.contains("hostile")) {
            return "Threats";
        }
        return "Core";
    }

    private Layout layout() {
        int layoutWidth = Math.min(Math.max(width - 24, 520), 980);
        int left = (width - layoutWidth) / 2;
        return new Layout(left, left + layoutWidth, 10, layoutWidth < 820);
    }

    private int listTop(Layout layout) {
        return layout.top() + 102;
    }

    private int rowHeight() {
        return 38;
    }

    private int rowsPerPage(Layout layout) {
        return Math.max(3, (height - listTop(layout) - 42) / rowHeight());
    }

    private void saveAndRebuild() {
        HapticSettingsStore.save();
        rebuild();
    }

    private Button button(int x, int y, int w, int h, String text, Button.OnPress onPress) {
        return Button.builder(Component.literal(text), onPress).bounds(x, y, w, h).build();
    }

    private ButtonSpec spec(String text, int width, Runnable action) {
        return new ButtonSpec(text, width, action);
    }

    private String nextPattern(String current) {
        for (int i = 0; i < HapticSettingsStore.PATTERNS.length; i++) {
            if (HapticSettingsStore.PATTERNS[i].equals(current)) {
                return HapticSettingsStore.PATTERNS[(i + 1) % HapticSettingsStore.PATTERNS.length];
            }
        }
        return HapticSettingsStore.PATTERNS[0];
    }

    private String nextColor(String current) {
        String clean = current == null ? "" : current;
        for (int i = 0; i < COLORS.length; i++) {
            if (COLORS[i].equalsIgnoreCase(clean)) {
                return COLORS[(i + 1) % COLORS.length];
            }
        }
        return COLORS[0];
    }

    private int nextCooldown(int current) {
        if (current < 100) {
            return 100;
        }
        if (current < 250) {
            return 250;
        }
        if (current < 500) {
            return 500;
        }
        if (current < 1000) {
            return 1000;
        }
        if (current < 2000) {
            return 2000;
        }
        return 0;
    }

    private String colorLabel(String color) {
        if (color == null || color.isBlank()) {
            return "No LED";
        }
        return color.replace("#", "");
    }

    private String fit(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        int ellipsisWidth = font.width("...");
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - ellipsisWidth)) + "...";
    }

    private String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Layout(int left, int right, int top, boolean compact) {
    }

    private record ButtonSpec(String text, int width, Runnable action) {
    }
}
