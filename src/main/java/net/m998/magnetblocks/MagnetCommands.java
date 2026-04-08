package net.m998.magnetblocks;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import java.util.*;
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
                .then(literal("list").executes(MagnetCommands::listPhantomMagnets))
                .then(literal("remove")
                        .then(argument("id", IntegerArgumentType.integer())
                                .executes(context -> removePhantomMagnet(context, IntegerArgumentType.getInteger(context, "id")))))
                .then(literal("clear").executes(MagnetCommands::clearAllPhantomMagnets))
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
                .then(literal("list").executes(MagnetCommands::whitelistList));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> adminCommands() {
        return literal("admin")
                .requires(source -> source.hasPermissionLevel(3))
                .then(literal("storm")
                        .then(literal("start")
                                .executes(MagnetCommands::adminStormStart)
                                .then(argument("duration", IntegerArgumentType.integer(1, 120))
                                        .executes(context -> adminStormStartWithDuration(context, IntegerArgumentType.getInteger(context, "duration")))
                                        .then(argument("intensity", DoubleArgumentType.doubleArg(0.1, 5.0))
                                                .executes(context -> adminStormStartWithDurationAndIntensity(context,
                                                        IntegerArgumentType.getInteger(context, "duration"),
                                                        DoubleArgumentType.getDouble(context, "intensity")))
                                                .then(argument("effect", StringArgumentType.word())
                                                        .suggests((context, builder) -> {
                                                            for (MagneticStormManager.StormEffect effect : MagneticStormManager.StormEffect.values())
                                                                builder.suggest(effect.name().toLowerCase());
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(context -> adminStormStartCustom(context,
                                                                IntegerArgumentType.getInteger(context, "duration"),
                                                                DoubleArgumentType.getDouble(context, "intensity"),
                                                                StringArgumentType.getString(context, "effect")))))))
                        .then(literal("stop").executes(MagnetCommands::adminStormStop))
                        .then(literal("enable")
                                .then(argument("value", BoolArgumentType.bool())
                                        .executes(context -> adminStormEnable(context, BoolArgumentType.getBool(context, "value")))))
                        .then(literal("status").executes(MagnetCommands::adminStormStatus)))
                .then(literal("debug").executes(MagnetCommands::adminDebugInfo));
    }

    private static PhantomMagnetManager getManager(CommandContext<ServerCommandSource> context) {
        ServerWorld world = context.getSource().getWorld();
        return PhantomMagnetManager.get(world);
    }

    private static int createPhantomMagnet(CommandContext<ServerCommandSource> context, BlockPos pos,
                                           double radius, double forceMultiplier, boolean attracting) {
        PhantomMagnetManager manager = getManager(context);
        int id = manager.createMagnet(pos, radius, forceMultiplier, attracting);
        context.getSource().sendFeedback(() -> Text.literal("Created phantom magnet #" + id + " at " + pos.toShortString()), true);
        return id;
    }

    private static int removePhantomMagnet(CommandContext<ServerCommandSource> context, int id) {
        PhantomMagnetManager manager = getManager(context);
        var magnet = manager.getMagnets().get(id);
        if (magnet != null && manager.removeMagnet(id)) {
            context.getSource().sendFeedback(() -> Text.literal("Removed magnet #" + id), true);
            return 1;
        } else {
            context.getSource().sendError(Text.literal("Magnet #" + id + " not found"));
            return 0;
        }
    }

    private static int listPhantomMagnets(CommandContext<ServerCommandSource> context) {
        PhantomMagnetManager manager = getManager(context);
        var magnets = manager.getMagnets();
        context.getSource().sendFeedback(() -> Text.literal("Phantom magnets in this dimension: " + magnets.size()), false);
        magnets.forEach((id, magnet) -> context.getSource().sendFeedback(() ->
                Text.literal("#" + id + " at " + magnet.getPos().toShortString() +
                        " r=" + magnet.getRadius() + " f=" + magnet.getForceMultiplier()), false));
        return magnets.size();
    }

    private static int clearAllPhantomMagnets(CommandContext<ServerCommandSource> context) {
        PhantomMagnetManager manager = getManager(context);
        if (manager.isClearConfirmationPending()) {
            int count = manager.getMagnetCount();
            manager.clearAllMagnets(true);
            context.getSource().sendFeedback(() -> Text.literal("Cleared " + count + " magnets"), true);
            return count;
        } else {
            manager.clearAllMagnets(false);
            context.getSource().sendFeedback(() -> Text.literal("Run command again to confirm clearing all magnets in this dimension"), true);
            return 0;
        }
    }

    private static int modifyMagnetRange(CommandContext<ServerCommandSource> context, int id, double newRange) {
        PhantomMagnetManager manager = getManager(context);
        var magnet = manager.getMagnets().get(id);
        if (magnet != null) {
            magnet.setRadius(newRange);
            manager.markDirty();
            context.getSource().sendFeedback(() -> Text.literal("Set magnet #" + id + " range to " + newRange), true);
            return 1;
        } else {
            context.getSource().sendError(Text.literal("Magnet not found"));
            return 0;
        }
    }

    private static int modifyMagnetForce(CommandContext<ServerCommandSource> context, int id, double newForce) {
        PhantomMagnetManager manager = getManager(context);
        var magnet = manager.getMagnets().get(id);
        if (magnet != null) {
            magnet.setForceMultiplier(newForce);
            manager.markDirty();
            context.getSource().sendFeedback(() -> Text.literal("Set magnet #" + id + " force to " + newForce), true);
            return 1;
        } else {
            context.getSource().sendError(Text.literal("Magnet not found"));
            return 0;
        }
    }

    private static int modifyMagnetPolarity(CommandContext<ServerCommandSource> context, int id, boolean newPolarity) {
        PhantomMagnetManager manager = getManager(context);
        var magnet = manager.getMagnets().get(id);
        if (magnet != null) {
            magnet.setAttracting(newPolarity);
            manager.markDirty();
            context.getSource().sendFeedback(() -> Text.literal("Set magnet #" + id + " polarity to " +
                    (newPolarity ? "attract" : "repel")), true);
            return 1;
        } else {
            context.getSource().sendError(Text.literal("Magnet not found"));
            return 0;
        }
    }

    private static int whitelistAdd(CommandContext<ServerCommandSource> context, String playerName, double strength) {
        MinecraftServer server = context.getSource().getServer();
        UUID uuid = getPlayerUUID(server, playerName);
        if (uuid == null) {
            context.getSource().sendError(Text.literal("Player not found"));
            return 0;
        }
        PhantomMagnetManager manager = getManager(context);
        manager.addPlayerToWhitelist(uuid, strength);
        context.getSource().sendFeedback(() -> Text.literal("Added " + playerName + " to whitelist with strength " + strength), true);
        return 1;
    }

    private static int whitelistRemove(CommandContext<ServerCommandSource> context, String playerName) {
        MinecraftServer server = context.getSource().getServer();
        UUID uuid = getPlayerUUID(server, playerName);
        if (uuid == null) {
            context.getSource().sendError(Text.literal("Player not found"));
            return 0;
        }
        PhantomMagnetManager manager = getManager(context);
        if (manager.removePlayerFromWhitelist(uuid)) {
            context.getSource().sendFeedback(() -> Text.literal("Removed " + playerName + " from whitelist"), true);
            return 1;
        } else {
            context.getSource().sendError(Text.literal("Player not in whitelist"));
            return 0;
        }
    }

    private static int whitelistList(CommandContext<ServerCommandSource> context) {
        PhantomMagnetManager manager = getManager(context);
        var whitelist = manager.getWhitelist();
        context.getSource().sendFeedback(() -> Text.literal("Whitelist entries: " + whitelist.size()), false);
        whitelist.forEach((uuid, entry) -> {
            String name = context.getSource().getServer().getUserCache().getByUuid(uuid)
                    .map(GameProfile::getName).orElse(uuid.toString());
            context.getSource().sendFeedback(() -> Text.literal(name + ": " + entry.getStrength()), false);
        });
        return whitelist.size();
    }

    private static UUID getPlayerUUID(MinecraftServer server, String name) {
        Optional<GameProfile> profile = server.getUserCache().findByName(name);
        if (profile.isPresent()) return profile.get().getId();
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
    }

    private static int adminStormStart(CommandContext<ServerCommandSource> context) {
        MagneticStormManager manager = MagneticStormManager.get(context.getSource().getServer());
        if (manager.isStormActive()) {
            context.getSource().sendError(Text.literal("Storm already active"));
            return 0;
        }
        manager.startStorm(context.getSource().getServer());
        context.getSource().sendFeedback(() -> Text.literal("Magnetic storm started"), true);
        return 1;
    }

    private static int adminStormStartWithDuration(CommandContext<ServerCommandSource> context, int duration) {
        MagneticStormManager manager = MagneticStormManager.get(context.getSource().getServer());
        if (manager.isStormActive()) {
            context.getSource().sendError(Text.literal("Storm already active"));
            return 0;
        }
        manager.startCustomStorm(context.getSource().getServer(), duration, 1.0, null);
        context.getSource().sendFeedback(() -> Text.literal("Magnetic storm started for " + duration + " minutes"), true);
        return 1;
    }

    private static int adminStormStartWithDurationAndIntensity(CommandContext<ServerCommandSource> context,
                                                               int duration, double intensity) {
        MagneticStormManager manager = MagneticStormManager.get(context.getSource().getServer());
        if (manager.isStormActive()) {
            context.getSource().sendError(Text.literal("Storm already active"));
            return 0;
        }
        manager.startCustomStorm(context.getSource().getServer(), duration, intensity, null);
        context.getSource().sendFeedback(() -> Text.literal("Storm started: " + duration + " min, intensity " + intensity), true);
        return 1;
    }

    private static int adminStormStartCustom(CommandContext<ServerCommandSource> context, int duration,
                                             double intensity, String effectName) {
        MagneticStormManager manager = MagneticStormManager.get(context.getSource().getServer());
        if (manager.isStormActive()) {
            context.getSource().sendError(Text.literal("Storm already active"));
            return 0;
        }
        MagneticStormManager.StormEffect effect;
        try {
            effect = MagneticStormManager.StormEffect.valueOf(effectName.toUpperCase());
        } catch (IllegalArgumentException e) {
            context.getSource().sendError(Text.literal("Invalid effect"));
            return 0;
        }
        manager.startCustomStorm(context.getSource().getServer(), duration, intensity, effect);
        context.getSource().sendFeedback(() -> Text.literal("Custom storm started"), true);
        return 1;
    }

    private static int adminStormStop(CommandContext<ServerCommandSource> context) {
        MagneticStormManager manager = MagneticStormManager.get(context.getSource().getServer());
        if (!manager.isStormActive()) {
            context.getSource().sendError(Text.literal("No active storm"));
            return 0;
        }
        manager.endStorm(context.getSource().getServer());
        context.getSource().sendFeedback(() -> Text.literal("Storm ended"), true);
        return 1;
    }

    private static int adminStormEnable(CommandContext<ServerCommandSource> context, boolean enable) {
        MagneticStormManager.ENABLE_MAGNETIC_STORMS = enable;
        context.getSource().sendFeedback(() -> Text.literal("Magnetic storms " + (enable ? "enabled" : "disabled")), true);
        return 1;
    }

    private static int adminStormStatus(CommandContext<ServerCommandSource> context) {
        MagneticStormManager manager = MagneticStormManager.get(context.getSource().getServer());
        MinecraftServer server = context.getSource().getServer();
        context.getSource().sendFeedback(() -> Text.literal("Storm active: " + manager.isStormActive()), false);
        context.getSource().sendFeedback(() -> Text.literal("Enabled: " + MagneticStormManager.ENABLE_MAGNETIC_STORMS), false);
        if (manager.isStormActive()) {
            long ticksLeft = manager.getStormTimeRemaining(server);
            context.getSource().sendFeedback(() -> Text.literal("Time left: " + (ticksLeft / 20 / 60) + " min"), false);
        } else {
            long ticksUntil = manager.getTimeUntilNextStorm(server);
            context.getSource().sendFeedback(() -> Text.literal("Next storm in: " + (ticksUntil / 20 / 60) + " min"), false);
        }
        return 1;
    }

    private static int adminDebugInfo(CommandContext<ServerCommandSource> context) {
        PhantomMagnetManager manager = getManager(context);
        context.getSource().sendFeedback(() -> Text.literal("Magnets in this dimension: " + manager.getMagnetCount()), false);
        context.getSource().sendFeedback(() -> Text.literal("Clear confirmation pending: " + manager.isClearConfirmationPending()), false);
        return 1;
    }
}
