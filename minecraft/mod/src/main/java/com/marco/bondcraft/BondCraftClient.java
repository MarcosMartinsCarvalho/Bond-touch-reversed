package com.marco.bondcraft;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

final class BondCraftClient {
    private static final MinecraftEventScanner SCANNER = new MinecraftEventScanner();
    private static final KeyMapping OPEN_SETTINGS = new KeyMapping(
            "key.bondcraft.open_settings",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "key.categories.bondcraft"
    );
    private static boolean registered;

    static void register(net.neoforged.bus.api.IEventBus modEventBus) {
        if (registered) {
            return;
        }
        registered = true;
        modEventBus.addListener(BondCraftClient::registerKeys);
        NeoForge.EVENT_BUS.addListener(BondCraftClient::onClientTick);
        BondCraft.LOGGER.info("BondCraft client event scanner registered.");
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SETTINGS);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (OPEN_SETTINGS.consumeClick()) {
            minecraft.setScreen(new HapticSettingsScreen(minecraft.screen));
        }
        SCANNER.tick();
    }
}
