package net.m998.magnetblocks;

import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModBlockEntities {
    public static BlockEntityType<MagnetBlockEntity> MAGNET_BLOCK_ENTITY;

    public static void register() {
        MAGNET_BLOCK_ENTITY = Registry.register(
                Registries.BLOCK_ENTITY_TYPE,
                new Identifier(MagnetBlocksMod.MOD_ID, "magnet_block_entity"),
                BlockEntityType.Builder.create(
                        MagnetBlockEntity::new,
                        ModBlocks.MAGNET_BLOCK
                ).build(null)
        );
    }
}