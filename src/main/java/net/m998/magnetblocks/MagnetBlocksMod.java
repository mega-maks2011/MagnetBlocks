package net.m998.magnetblocks;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public class MagnetBlocksMod implements ModInitializer {
    public static final String MOD_ID = "magnetblocks";

    @Override
    public void onInitialize() {
        ModBlocks.register();
        ModBlockEntities.register();
        ModItems.register();

        ServerTickEvents.END_WORLD_TICK.register(MagnetBlock::tickPropagation);

        ServerTickEvents.START_SERVER_TICK.register(server -> {
            MagneticStormManager stormManager = MagneticStormManager.get(server);
            stormManager.tick(server);

            MagnetWhitelistManager.get(server); // Initialize whitelist manager

            PhantomMagnetManager phantomManager = PhantomMagnetManager.get(server);
            if (!phantomManager.getMagnets().isEmpty()) {
                for (var world : server.getWorlds()) {
                    MagnetBlockEntity.processAllPhantomMagnets(world);
                }
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> MagnetCommands.register(dispatcher));
    }
}
