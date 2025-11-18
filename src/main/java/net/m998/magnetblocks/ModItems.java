package net.m998.magnetblocks;

import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class ModItems {
    public static final Item MAGNET_ITEM = new BlockItem(
            ModBlocks.MAGNET_BLOCK,
            new Item.Settings()
    );

    public static void register() {
        Registry.register(Registries.ITEM,
                new Identifier(MagnetBlocksMod.MOD_ID, "magnet"),
                MAGNET_ITEM);

        // Create creative mode item group
        ItemGroup MAGNETS_GROUP = ItemGroup.create(ItemGroup.Row.TOP, 0)
                .displayName(Text.translatable("itemGroup.magnetblocks.magnets"))
                .icon(() -> new ItemStack(MAGNET_ITEM))
                .entries((displayContext, entries) -> entries.add(MAGNET_ITEM))
                .build();

        Registry.register(Registries.ITEM_GROUP,
                new Identifier(MagnetBlocksMod.MOD_ID, "magnets"),
                MAGNETS_GROUP);
    }
}