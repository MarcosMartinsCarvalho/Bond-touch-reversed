package com.marco.bondcraft;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class BondCraftConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Master switch for sending local Minecraft events to the BondCraft PC companion.")
            .define("enabled", true);

    public static final ModConfigSpec.ConfigValue<String> BRIDGE_URL = BUILDER
            .comment("Local PC companion endpoint. The companion listens here by default.")
            .define("bridgeUrl", "http://127.0.0.1:8765/event");

    public static final ModConfigSpec.IntValue CREEPER_RADIUS = BUILDER
            .comment("Blocks around the player to scan for creeper warnings.")
            .defineInRange("creeperRadius", 9, 2, 32);

    public static final ModConfigSpec.IntValue HOSTILE_RADIUS = BUILDER
            .comment("Blocks around the player to scan for hostile mob warnings.")
            .defineInRange("hostileRadius", 7, 2, 32);

    public static final ModConfigSpec.IntValue STEP_DISTANCE_CM = BUILDER
            .comment("Horizontal travel distance in centimeters before a walking/sprinting step event.")
            .defineInRange("stepDistanceCm", 120, 40, 400);

    public static final ModConfigSpec.BooleanValue VERBOSE_LOGGING = BUILDER
            .comment("Log every event sent to the companion.")
            .define("verboseLogging", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private BondCraftConfig() {
    }
}
