package net.m998.magnetblocks;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public class MagnetBlocksMod implements ModInitializer {
    public static final String MOD_ID = "magnetblocks";

    @Override
    public void onInitialize() {
        ModBlocks.register();
        ModBlockEntities.register();
        ModItems.register();
        // Register world tick for magnet propagation system
        ServerTickEvents.END_WORLD_TICK.register(MagnetBlock::tickPropagation);
    }
}