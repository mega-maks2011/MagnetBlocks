package net.m998.magnetblocks;

import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModBlocks {
    public static final Block MAGNET_BLOCK = new MagnetBlock(
            Block.Settings.create()
                    .strength(2.0f, 6.0f)
                    .requiresTool()
    );

    public static void register() {
        Registry.register(Registries.BLOCK, new Identifier(MagnetBlocksMod.MOD_ID, "magnet"), MAGNET_BLOCK);
    }
}
