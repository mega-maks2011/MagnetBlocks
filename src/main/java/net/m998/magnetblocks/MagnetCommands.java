package net.m998.magnetblocks;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.server.MinecraftServer;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import static net.minecraft.server.command.CommandManager.*;

public class MagnetCommands {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("magnet")
                .requires(source -> source.hasPermissionLevel(2))
                .then(createCommands())
                .then(manageCommands())
                .then(whitelistCommands())
                .then(adminCommands()));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> createCommands() {
        return literal("create")
                .then(argument("pos", BlockPosArgumentType.blockPos())
                        .then(argument("radius", DoubleArgumentType.doubleArg(1.0, 500.0))
                                .then(argument("force", DoubleArgumentType.doubleArg(0.01, 10.0))
                                        .then(argument("attracting", BoolArgumentType.bool())
                                                .executes(context -> createPhantomMagnet(context,
                                                        BlockPosArgumentType.getBlockPos(context, "pos"),
                                                        DoubleArgumentType.getDouble(context, "radius"),
                                                        DoubleArgumentType.getDouble(context, "force"),
                                                        BoolArgumentType.getBool(context, "attracting")))))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> manageCommands() {
        return literal("manage")
                .then(literal("list")
                        .executes(MagnetCommands::listPhantomMagnets))
                .then(literal("remove")
                        .then(argument("id", IntegerArgumentType.integer())
                                .executes(context -> removePhantomMagnet(context, IntegerArgumentType.getInteger(context, "id")))))
                .then(literal("clear")
                        .executes(MagnetCommands::clearAllPhantomMagnets))
                .then(literal("modify")
                        .then(argument("id", IntegerArgumentType.integer())
                                .then(literal("range")
                                        .then(argument("value", DoubleArgumentType.doubleArg(1.0, 500.0))
                                                .executes(context -> modifyMagnetRange(context, IntegerArgumentType.getInteger(context, "id"), DoubleArgumentType.getDouble(context, "value")))))
                                .then(literal("force")
                                        .then(argument("value", DoubleArgumentType.doubleArg(0.01, 10.0))
                                                .executes(context -> modifyMagnetForce(context, IntegerArgumentType.getInteger(context, "id"), DoubleArgumentType.getDouble(context, "value")))))
                                .then(literal("polarity")
                                        .then(argument("value", BoolArgumentType.bool())
                                                .executes(context -> modifyMagnetPolarity(context, IntegerArgumentType.getInteger(context, "id"), BoolArgumentType.getBool(context, "value")))))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> whitelistCommands() {
        return literal("whitelist")
                .then(literal("add")
                        .then(argument("player", StringArgumentType.string())
                                .then(argument("strength", DoubleArgumentType.doubleArg(0.001, 5.0))
                                        .executes(context -> whitelistAdd(context, StringArgumentType.getString(context, "player"), DoubleArgumentType.getDouble(context, "strength"))))))
                .then(literal("remove")
                        .then(argument("player", StringArgumentType.string())
                                .executes(context -> whitelistRemove(context, StringArgumentType.getString(context, "player")))))
                .then(literal("list")
                        .executes(MagnetCommands::whitelistList));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> adminCommands() {
        return literal("admin")
                .requires(source -> source.hasPermissionLevel(3))
                .then(literal("storm")
                        .then(literal("start")
                                .executes(context -> adminStormStart(context)) // Простой запуск
                                .then(argument("duration", IntegerArgumentType.integer(1, 120))
                                        .executes(context -> adminStormStartWithDuration(context, IntegerArgumentType.getInteger(context, "duration")))
                                        .then(argument("intensity", DoubleArgumentType.doubleArg(0.1, 5.0))
                                                .executes(context -> adminStormStartWithDurationAndIntensity(context,
                                                        IntegerArgumentType.getInteger(context, "duration"),
                                                        DoubleArgumentType.getDouble(context, "intensity")))
                                                .then(argument("effect", StringArgumentType.word())
                                                        .suggests((context, builder) -> {
                                                            for (String effect : MagneticStormManager.StormEffect.getEffectNames()) {
                                                                builder.suggest(effect);
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(context -> adminStormStartCustom(context,
                                                                IntegerArgumentType.getInteger(context, "duration"),
                                                                DoubleArgumentType.getDouble(context, "intensity"),
                                                                StringArgumentType.getString(context, "effect")))))))
                        .then(literal("stop")
                                .executes(context -> adminStormStop(context)))
                        .then(literal("enable")
                                .then(argument("value", BoolArgumentType.bool())
                                        .executes(context -> adminStormEnable(context, BoolArgumentType.getBool(context, "value")))))
                        .then(literal("status")
                                .executes(MagnetCommands::adminStormStatus)))
                .then(literal("debug")
                        .executes(MagnetCommands::adminDebugInfo));
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
        var magnet = manager.getMagnets().get(id);

        if (magnet != null && manager.removeMagnet(id)) {
            stopBeaconSoundForMagnet(context.getSource().getWorld(), magnet.getPos());
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.remove.success", id), true);
            return 1;
        } else {
            context.getSource().sendError(Text.translatable("command.magnetblocks.remove.error", id));
            return 0;
        }
    }

    private static int modifyMagnetRange(CommandContext<ServerCommandSource> context, int id, double newRange) {
        return modifyMagnetProperty(context, id, magnet -> {
            magnet.setRadius(newRange);
            return Text.translatable("command.magnetblocks.modify.range.success", id, newRange);
        });
    }

    private static int modifyMagnetForce(CommandContext<ServerCommandSource> context, int id, double newForce) {
        return modifyMagnetProperty(context, id, magnet -> {
            double actualForce = Math.min(newForce, PhantomMagnetManager.getMaxForceMultiplier());
            magnet.setForceMultiplier(actualForce);

            if (newForce > actualForce) {
                context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.modify.force_limited", newForce, actualForce), false);
            }

            return Text.translatable("command.magnetblocks.modify.force.success", id, actualForce);
        });
    }

    private static int modifyMagnetPolarity(CommandContext<ServerCommandSource> context, int id, boolean newPolarity) {
        return modifyMagnetProperty(context, id, magnet -> {
            magnet.setAttracting(newPolarity);
            return Text.translatable("command.magnetblocks.modify.polarity.success", id,
                    newPolarity ? Text.translatable("command.magnetblocks.polarity.attracting") : Text.translatable("command.magnetblocks.polarity.repelling"));
        });
    }

    private static int modifyMagnetProperty(CommandContext<ServerCommandSource> context, int id, MagnetModifier modifier) {
        PhantomMagnetManager manager = PhantomMagnetManager.get(context.getSource().getServer());
        var magnet = manager.getMagnets().get(id);

        if (magnet != null) {
            Text resultMessage = modifier.modify(magnet);
            manager.markDirty();
            context.getSource().sendFeedback(() -> resultMessage, true);
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
            magnets.forEach((id, magnet) -> context.getSource().sendFeedback(() ->
                    Text.translatable("command.magnetblocks.list.entry", id, magnet.getPos().toShortString(),
                            magnet.getRadius(), magnet.getForceMultiplier(),
                            magnet.isAttracting() ? Text.translatable("command.magnetblocks.polarity.attracting") : Text.translatable("command.magnetblocks.polarity.repelling")), false));
        }
        return magnets.size();
    }

    private static int clearAllPhantomMagnets(CommandContext<ServerCommandSource> context) {
        PhantomMagnetManager manager = PhantomMagnetManager.get(context.getSource().getServer());

        if (manager.isClearConfirmationPending()) {
            int magnetCount = manager.getMagnetCount();

            // Останавливаем звуки для всех магнитов
            manager.getMagnets().forEach((id, magnet) ->
                    stopBeaconSoundForMagnet(context.getSource().getWorld(), magnet.getPos()));

            manager.clearAllMagnets(true);
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.clear.success", magnetCount), true);
            return magnetCount;
        } else {
            manager.clearAllMagnets(false);
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.clear.confirm"), true);
            return 0;
        }
    }

    private static int whitelistAdd(CommandContext<ServerCommandSource> context, String playerName, double strength) {
        try {
            MinecraftServer server = context.getSource().getServer();
            UUID playerUUID = Objects.requireNonNull(server.getUserCache()).findByName(playerName).orElseThrow().getId();
            MagnetWhitelistManager.get(server).addPlayer(playerUUID, strength);
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.whitelist.add.success", playerName, strength), true);
            return 1;
        } catch (Exception e) {
            context.getSource().sendError(Text.translatable("command.magnetblocks.whitelist.add.error", playerName));
            return 0;
        }
    }

    private static int whitelistRemove(CommandContext<ServerCommandSource> context, String playerName) {
        try {
            MinecraftServer server = context.getSource().getServer();
            UUID playerUUID = Objects.requireNonNull(server.getUserCache()).findByName(playerName).orElseThrow().getId();
            boolean removed = MagnetWhitelistManager.get(server).removePlayer(playerUUID);

            if (removed) {
                context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.whitelist.remove.success", playerName), true);
                return 1;
            } else {
                context.getSource().sendError(Text.translatable("command.magnetblocks.whitelist.remove.error", playerName));
                return 0;
            }
        } catch (Exception e) {
            context.getSource().sendError(Text.translatable("command.magnetblocks.whitelist.remove.error", playerName));
            return 0;
        }
    }

    private static int whitelistList(CommandContext<ServerCommandSource> context) {
        var whitelist = MagnetWhitelistManager.get(context.getSource().getServer()).getWhitelist();

        if (whitelist.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.whitelist.list.empty"), false);
        } else {
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.whitelist.list.header", whitelist.size()), false);
            whitelist.forEach((uuid, entry) -> {
                String playerName = Objects.requireNonNull(context.getSource().getServer().getUserCache())
                        .getByUuid(uuid).map(GameProfile::getName).orElse("Unknown");
                context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.whitelist.list.entry", playerName, entry.getStrength()), false);
            });
        }
        return whitelist.size();
    }

    private static int adminStormStart(CommandContext<ServerCommandSource> context) {
        MagneticStormManager stormManager = MagneticStormManager.get(context.getSource().getServer());

        if (stormManager.isStormActive()) {
            context.getSource().sendError(Text.translatable("command.magnetblocks.admin.storm.start.error.active"));
            return 0;
        }

        stormManager.startStorm(context.getSource().getServer());
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.storm.start.success"), true);
        return 1;
    }

    private static int adminStormStartWithDuration(CommandContext<ServerCommandSource> context, int duration) {
        MagneticStormManager stormManager = MagneticStormManager.get(context.getSource().getServer());

        if (stormManager.isStormActive()) {
            context.getSource().sendError(Text.translatable("command.magnetblocks.admin.storm.start.error.active"));
            return 0;
        }

        stormManager.startCustomStorm(context.getSource().getServer(), duration, 1.0, null);
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.storm.start.duration.success", duration), true);
        return 1;
    }

    private static int adminStormStartWithDurationAndIntensity(CommandContext<ServerCommandSource> context, int duration, double intensity) {
        MagneticStormManager stormManager = MagneticStormManager.get(context.getSource().getServer());

        if (stormManager.isStormActive()) {
            context.getSource().sendError(Text.translatable("command.magnetblocks.admin.storm.start.error.active"));
            return 0;
        }

        stormManager.startCustomStorm(context.getSource().getServer(), duration, intensity, null);
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.storm.start.duration_intensity.success", duration, intensity), true);
        return 1;
    }

    private static int adminStormStartCustom(CommandContext<ServerCommandSource> context, int duration, double intensity, String effectName) {
        MagneticStormManager stormManager = MagneticStormManager.get(context.getSource().getServer());

        if (stormManager.isStormActive()) {
            context.getSource().sendError(Text.translatable("command.magnetblocks.admin.storm.start.error.active"));
            return 0;
        }

        MagneticStormManager.StormEffect effect = MagneticStormManager.StormEffect.fromString(effectName);
        if (effect == null) {
            context.getSource().sendError(Text.translatable("command.magnetblocks.admin.storm.start.error.invalid_effect", effectName));
            return 0;
        }

        stormManager.startCustomStorm(context.getSource().getServer(), duration, intensity, effect);
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.storm.start.custom.success",
                duration, intensity, effect.getDisplayName()), true);
        return 1;
    }

    private static int adminStormStop(CommandContext<ServerCommandSource> context) {
        MagneticStormManager stormManager = MagneticStormManager.get(context.getSource().getServer());

        if (!stormManager.isStormActive()) {
            context.getSource().sendError(Text.translatable("command.magnetblocks.admin.storm.stop.error.inactive"));
            return 0;
        }

        stormManager.endStorm(context.getSource().getServer());
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.storm.stop.success"), true);
        return 1;
    }

    private static int adminStormEnable(CommandContext<ServerCommandSource> context, boolean enable) {
        MagneticStormManager.ENABLE_MAGNETIC_STORMS = enable;
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.storm." + (enable ? "enabled" : "disabled")), true);
        return 1;
    }

    private static int adminStormStatus(CommandContext<ServerCommandSource> context) {
        MagneticStormManager stormManager = MagneticStormManager.get(context.getSource().getServer());

        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.storm.header"), false);
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.storm.active", stormManager.isStormActive()), false);
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.storm.enabled", MagneticStormManager.ENABLE_MAGNETIC_STORMS), false);

        if (stormManager.isStormActive()) {
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.storm.time_remaining",
                    stormManager.getStormTimeRemaining() / 60000), false);
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.storm.intensity",
                    stormManager.getStormIntensity()), false);
            if (stormManager.getCurrentEffect() != null) {
                context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.storm.effect",
                        stormManager.getCurrentEffect().getDisplayName()), false);
            }
        } else {
            context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.storm.time_until",
                    stormManager.getTimeUntilNextStorm() / 60000), false);
        }
        return 1;
    }

    private static int adminDebugInfo(CommandContext<ServerCommandSource> context) {
        PhantomMagnetManager manager = PhantomMagnetManager.get(context.getSource().getServer());
        var magnets = manager.getMagnets();

        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.debug.header"), false);
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.debug.magnets_count", magnets.size()), false);
        context.getSource().sendFeedback(() -> Text.translatable("command.magnetblocks.admin.debug.clear_pending", manager.isClearConfirmationPending()), false);

        return 1;
    }

    private static void stopBeaconSoundForMagnet(World world, BlockPos pos) {
        if (world instanceof ServerWorld serverWorld) {
            List<ServerPlayerEntity> players = serverWorld.getPlayers(player ->
                    player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 256);

            for (ServerPlayerEntity player : players) {
                player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.StopSoundS2CPacket(
                        SoundEvents.BLOCK_BEACON_AMBIENT.getId(), SoundCategory.BLOCKS));
            }
        }
    }

    @FunctionalInterface
    private interface MagnetModifier {
        Text modify(PhantomMagnetManager.PhantomMagnet magnet);
    }
}
