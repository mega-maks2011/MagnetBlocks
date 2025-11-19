package net.m998.magnetblocks;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import static net.minecraft.server.command.CommandManager.*;

public class MagnetCommands {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("magnet")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("create")
                        .then(argument("pos", BlockPosArgumentType.blockPos())
                                .then(argument("radius", DoubleArgumentType.doubleArg(1.0, 500.0))
                                        .then(argument("forceMultiplier", DoubleArgumentType.doubleArg(0.1, 10.0))
                                                .then(argument("attracting", BoolArgumentType.bool())
                                                        .executes(context -> createPhantomMagnet(context,
                                                                BlockPosArgumentType.getBlockPos(context, "pos"),
                                                                DoubleArgumentType.getDouble(context, "radius"),
                                                                DoubleArgumentType.getDouble(context, "forceMultiplier"),
                                                                BoolArgumentType.getBool(context, "attracting"))))))))
                .then(literal("remove")
                        .then(argument("id", IntegerArgumentType.integer())
                                .executes(context -> removePhantomMagnet(context, IntegerArgumentType.getInteger(context, "id")))))
                .then(literal("modify")
                        .then(argument("id", IntegerArgumentType.integer())
                                .then(literal("range")
                                        .then(argument("value", DoubleArgumentType.doubleArg(1.0, 500.0))
                                                .executes(context -> modifyMagnetRange(context, IntegerArgumentType.getInteger(context, "id"), DoubleArgumentType.getDouble(context, "value")))))
                                .then(literal("force")
                                        .then(argument("value", DoubleArgumentType.doubleArg(0.1, 10.0))
                                                .executes(context -> modifyMagnetForce(context, IntegerArgumentType.getInteger(context, "id"), DoubleArgumentType.getDouble(context, "value")))))
                                .then(literal("polarity")
                                        .then(argument("value", BoolArgumentType.bool())
                                                .executes(context -> modifyMagnetPolarity(context, IntegerArgumentType.getInteger(context, "id"), BoolArgumentType.getBool(context, "value")))))))
                .then(literal("list")
                        .executes(MagnetCommands::listPhantomMagnets))
                .then(literal("clear")
                        .executes(MagnetCommands::clearAllPhantomMagnets))
                .then(literal("admin")
                        .requires(source -> source.hasPermissionLevel(3))
                        .then(literal("info")
                                .then(argument("id", IntegerArgumentType.integer())
                                        .executes(context -> adminMagnetInfo(context, IntegerArgumentType.getInteger(context, "id")))))
                        .then(literal("debug")
                                .executes(MagnetCommands::adminDebugInfo))));
    }

    private static int createPhantomMagnet(CommandContext<ServerCommandSource> context, BlockPos pos, double radius, double forceMultiplier, boolean attracting) {
        PhantomMagnetManager manager = PhantomMagnetManager.get(context.getSource().getServer());
        int id = manager.createMagnet(pos, radius, forceMultiplier, attracting);
        double actualForce = Math.min(forceMultiplier, PhantomMagnetManager.getMaxForceMultiplier());
        if (forceMultiplier > actualForce) {
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.create.force_limited", forceMultiplier, actualForce), false);
        }
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.create.success", id, pos.toShortString(), radius, actualForce,
                attracting ? Text.translatable("command.magnetblocks.polarity.attracting") : Text.translatable("command.magnetblocks.polarity.repelling")), true);
        return id;
    }

    private static int removePhantomMagnet(CommandContext<ServerCommandSource> context, int id) {
        PhantomMagnetManager manager = PhantomMagnetManager.get(context.getSource().getServer());
        boolean removed = manager.removeMagnet(id);
        if (removed) {
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.remove.success", id), true);
            return 1;
        } else {
            context.getSource().sendError(Text.translatable("command.magnetblocks.remove.error", id));
            return 0;
        }
    }

    private static int modifyMagnetRange(CommandContext<ServerCommandSource> context, int id, double newRange) {
        PhantomMagnetManager manager = PhantomMagnetManager.get(context.getSource().getServer());
        var magnet = manager.getMagnets().get(id);
        if (magnet != null) {
            magnet.setRadius(newRange);
            manager.markDirty();
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.modify.range.success", id, newRange), true);
            return 1;
        } else {
            context.getSource().sendError(Text.translatable("command.magnetblocks.modify.error.not_found", id));
            return 0;
        }
    }

    private static int modifyMagnetForce(CommandContext<ServerCommandSource> context, int id, double newForce) {
        PhantomMagnetManager manager = PhantomMagnetManager.get(context.getSource().getServer());
        var magnet = manager.getMagnets().get(id);
        if (magnet != null) {
            double actualForce = Math.min(newForce, PhantomMagnetManager.getMaxForceMultiplier());
            magnet.setForceMultiplier(actualForce);
            manager.markDirty();
            if (newForce > actualForce) {
                context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.modify.force_limited", newForce, actualForce), false);
            }
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.modify.force.success", id, actualForce), true);
            return 1;
        } else {
            context.getSource().sendError(Text.translatable("command.magnetblocks.modify.error.not_found", id));
            return 0;
        }
    }

    private static int modifyMagnetPolarity(CommandContext<ServerCommandSource> context, int id, boolean newPolarity) {
        PhantomMagnetManager manager = PhantomMagnetManager.get(context.getSource().getServer());
        var magnet = manager.getMagnets().get(id);
        if (magnet != null) {
            magnet.setAttracting(newPolarity);
            manager.markDirty();
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.modify.polarity.success", id,
                    newPolarity ? Text.translatable("command.magnetblocks.polarity.attracting") : Text.translatable("command.magnetblocks.polarity.repelling")), true);
            return 1;
        } else {
            context.getSource().sendError(Text.translatable("command.magnetblocks.modify.error.not_found", id));
            return 0;
        }
    }

    private static int listPhantomMagnets(CommandContext<ServerCommandSource> context) {
        PhantomMagnetManager manager = PhantomMagnetManager.get(context.getSource().getServer());
        var magnets = manager.getMagnets();
        if (magnets.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.list.empty"), false);
        } else {
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.list.header", magnets.size()), false);
            for (var entry : magnets.entrySet()) {
                PhantomMagnetManager.PhantomMagnet magnet = entry.getValue();
                context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.list.entry", entry.getKey(), magnet.getPos().toShortString(),
                        magnet.getRadius(), magnet.getForceMultiplier(),
                        magnet.isAttracting() ? Text.translatable("command.magnetblocks.polarity.attracting") : Text.translatable("command.magnetblocks.polarity.repelling")), false);
            }
        }
        return magnets.size();
    }

    private static int clearAllPhantomMagnets(CommandContext<ServerCommandSource> context) {
        PhantomMagnetManager manager = PhantomMagnetManager.get(context.getSource().getServer());
        if (manager.isClearConfirmationPending()) {
            manager.clearAllMagnets(true);
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.clear.success", manager.getMagnets().size()), true);
            return 1;
        } else {
            manager.clearAllMagnets(false);
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.clear.confirm"), true);
            return 0;
        }
    }

    private static int adminMagnetInfo(CommandContext<ServerCommandSource> context, int id) {
        PhantomMagnetManager manager = PhantomMagnetManager.get(context.getSource().getServer());
        var magnet = manager.getMagnets().get(id);
        if (magnet != null) {
            BlockPos pos = magnet.getPos();
            World world = context.getSource().getWorld();
            String blockName = world.getBlockState(pos).getBlock().getTranslationKey();
            String biomeName = world.getBiome(pos).getKey().map(key -> key.getValue().toString()).orElse("unknown");
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.info.detailed", id, pos.toShortString(),
                    magnet.getRadius(), magnet.getForceMultiplier(),
                    magnet.isAttracting() ? Text.translatable("command.magnetblocks.polarity.attracting") : Text.translatable("command.magnetblocks.polarity.repelling"),
                    Text.translatable(blockName), biomeName, world.getRegistryKey().getValue().toString()), false);
            return 1;
        } else {
            context.getSource().sendError(Text.translatable("command.magnetblocks.modify.error.not_found", id));
            return 0;
        }
    }

    private static int adminDebugInfo(CommandContext<ServerCommandSource> context) {
        PhantomMagnetManager manager = PhantomMagnetManager.get(context.getSource().getServer());
        var magnets = manager.getMagnets();
        ServerPlayerEntity player = context.getSource().getPlayer();
        String playerName = player != null ? player.getGameProfile().getName() : "Console";
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.debug.header"), false);
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.debug.player", playerName), false);
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.debug.magnets_count", magnets.size()), false);
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.debug.next_id",
                manager.getMagnets().isEmpty() ? 1 : manager.getMagnets().keySet().stream().max(Integer::compareTo).orElse(0) + 1), false);
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.debug.clear_pending", manager.isClearConfirmationPending()), false);
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.debug.max_force", PhantomMagnetManager.getMaxForceMultiplier()), false);
        return 1;
    }
}
