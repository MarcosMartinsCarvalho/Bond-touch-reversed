package com.marco.bondcraft;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(BondCraft.MODID)
public final class BondCraft {
    public static final String MODID = "bondcraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BondCraft(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, BondCraftConfig.SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            BondCraftClient.register(modEventBus);
        }
        LOGGER.info("BondCraft Haptics loaded.");
    }
}
