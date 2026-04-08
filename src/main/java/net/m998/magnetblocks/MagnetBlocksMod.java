package net.m998.magnetblocks;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.world.ServerWorld;

public class MagnetBlocksMod implements ModInitializer {
    public static final String MOD_ID = "magnetblocks";

    @Override
    public void onInitialize() {
        ModBlocks.register();
        ModBlockEntities.register();
        ModItems.register();
        ServerTickEvents.END_WORLD_TICK.register(MagnetBlock::tickPropagation);
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (!world.isClient && world instanceof ServerWorld) {
                ServerWorld serverWorld = (ServerWorld) world;
                MagnetBlockEntity.processAllPhantomMagnets(serverWorld);
                MagneticStormManager stormManager = MagneticStormManager.get(serverWorld.getServer());
                stormManager.tick(serverWorld.getServer());
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                MagnetCommands.register(dispatcher));
    }
}
