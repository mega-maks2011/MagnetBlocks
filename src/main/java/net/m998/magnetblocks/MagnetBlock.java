package net.m998.magnetblocks;

import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.*;

public class MagnetBlock extends Block implements BlockEntityProvider {
    public static final BooleanProperty POWERED = Properties.POWERED;
    public static final BooleanProperty ATTRACTING = BooleanProperty.of("attracting");
    public static final BooleanProperty OVERHEATED = BooleanProperty.of("overheated");
    public static final BooleanProperty SUPERCONDUCTING = BooleanProperty.of("superconducting");
    public static final IntProperty TEMPERATURE = IntProperty.of("temperature", 0, 20);

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Map<World, Queue<PropagationTask>> propagationQueues = new WeakHashMap<>();
    private static final ThreadLocal<Set<BlockPos>> updatingBlocks = ThreadLocal.withInitial(HashSet::new);

    private static final Map<Block, Integer> TEMPERATURE_EFFECTS = new HashMap<>();
    static {
        // Heat sources
        TEMPERATURE_EFFECTS.put(Blocks.LAVA, 8);
        TEMPERATURE_EFFECTS.put(Blocks.FIRE, 6);
        TEMPERATURE_EFFECTS.put(Blocks.MAGMA_BLOCK, 5);
        TEMPERATURE_EFFECTS.put(Blocks.CAMPFIRE, 4);
        TEMPERATURE_EFFECTS.put(Blocks.SOUL_CAMPFIRE, 3);
        TEMPERATURE_EFFECTS.put(Blocks.FURNACE, 4);
        TEMPERATURE_EFFECTS.put(Blocks.BLAST_FURNACE, 5);
        TEMPERATURE_EFFECTS.put(Blocks.SMOKER, 4);
        TEMPERATURE_EFFECTS.put(Blocks.TORCH, 2);
        TEMPERATURE_EFFECTS.put(Blocks.SOUL_TORCH, 1);
        TEMPERATURE_EFFECTS.put(Blocks.LANTERN, 1);
        TEMPERATURE_EFFECTS.put(Blocks.SOUL_LANTERN, 1);
        // Cold sources
        TEMPERATURE_EFFECTS.put(Blocks.BLUE_ICE, -6);
        TEMPERATURE_EFFECTS.put(Blocks.PACKED_ICE, -4);
        TEMPERATURE_EFFECTS.put(Blocks.ICE, -3);
        TEMPERATURE_EFFECTS.put(Blocks.SNOW_BLOCK, -2);
        TEMPERATURE_EFFECTS.put(Blocks.POWDER_SNOW, -2);
        TEMPERATURE_EFFECTS.put(Blocks.SNOW, -1);
    }

    private static final Set<Block> MELTABLE_ICE_BLOCKS = Set.of(Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.SNOW);

    protected MagnetBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState()
                .with(POWERED, false).with(ATTRACTING, true).with(OVERHEATED, false)
                .with(SUPERCONDUCTING, false).with(TEMPERATURE, 10));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(POWERED, ATTRACTING, OVERHEATED, SUPERCONDUCTING, TEMPERATURE);
    }

    @Override
    public ItemStack getPickStack(BlockView world, BlockPos pos, BlockState state) {
        return new ItemStack(ModItems.MAGNET_ITEM);
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            if (!world.isClient && !moved) stopBeaconSoundForNearbyPlayers((ServerWorld) world, pos);
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof MagnetBlockEntity) world.removeBlockEntity(pos);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    private void stopBeaconSoundForNearbyPlayers(ServerWorld world, BlockPos pos) {
        List<ServerPlayerEntity> players = world.getPlayers(player ->
                player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 256);
        for (ServerPlayerEntity player : players) {
            player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.StopSoundS2CPacket(
                    SoundEvents.BLOCK_BEACON_AMBIENT.getId(), SoundCategory.BLOCKS));
        }
    }

    @Override
    public void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock, BlockPos sourcePos, boolean notify) {
        if (world.isClient) return;
        if (updatingBlocks.get().contains(pos)) return;

        try {
            updatingBlocks.get().add(pos);
            updateTemperature(world, pos, state);

            boolean isRedstoneUpdate = sourceBlock.getDefaultState().emitsRedstonePower() || world.getBlockState(sourcePos).getBlock().getDefaultState().emitsRedstonePower();
            if (!isRedstoneUpdate) {
                boolean hasLocalPower = world.isReceivingRedstonePower(pos);
                if (hasLocalPower && !state.get(POWERED)) world.setBlockState(pos, state.with(POWERED, true), 2);
                return;
            }

            boolean powered = world.isReceivingRedstonePower(pos);
            boolean currentPowered = state.get(POWERED);
            if (powered != currentPowered) {
                world.setBlockState(pos, state.with(POWERED, powered), 2);
                if (!powered) world.playSound(null, pos, SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.BLOCKS, 0.5F, 0.8F);
                else stopBeaconSoundForNearbyPlayers((ServerWorld) world, pos);

                Set<BlockPos> visited = new HashSet<>();
                visited.add(pos);
                for (Direction direction : DIRECTIONS) {
                    BlockPos neighborPos = pos.offset(direction);
                    if (!visited.contains(neighborPos)) {
                        BlockState neighborState = world.getBlockState(neighborPos);
                        if (neighborState.getBlock() instanceof MagnetBlock) addToPropagationQueue(world, new PowerChangeTask(neighborPos, powered, 0));
                    }
                }
            }
        } finally {
            updatingBlocks.get().remove(pos);
        }
    }

    private void updateTemperature(World world, BlockPos pos, BlockState state) {
        if (world.isClient) return;

        int totalTemperatureEffect = 0;
        boolean foundExtremeCold = false;

        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.offset(direction);
            BlockState neighborState = world.getBlockState(neighborPos);
            Block neighborBlock = neighborState.getBlock();

            if (TEMPERATURE_EFFECTS.containsKey(neighborBlock)) {
                int effect = TEMPERATURE_EFFECTS.get(neighborBlock);
                totalTemperatureEffect += effect;
                if (effect <= -4) foundExtremeCold = true;

                if (world.random.nextInt(8) == 0) {
                    if (effect > 0) world.addParticle(ParticleTypes.LAVA, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 0, 0, 0);
                    else if (effect < 0) world.addParticle(ParticleTypes.SNOWFLAKE, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 0, 0, 0);
                }

                if (MELTABLE_ICE_BLOCKS.contains(neighborBlock)) {
                    int meltChance = getMeltChance(neighborBlock);
                    if (world.random.nextInt(meltChance) == 0) {
                        if (neighborBlock == Blocks.SNOW) world.breakBlock(neighborPos, false);
                        else world.setBlockState(neighborPos, Blocks.WATER.getDefaultState());
                        world.playSound(null, neighborPos, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 0.5F, 1.0F);
                    }
                }
            }
        }

        int baseTemperature = 10;
        int newTemp = Math.max(0, Math.min(20, baseTemperature + totalTemperatureEffect));
        int currentTemp = state.get(TEMPERATURE);

        if (newTemp != currentTemp) {
            BlockState newState = state.with(TEMPERATURE, newTemp);
            if (newTemp >= 18 && !state.get(OVERHEATED)) {
                newState = newState.with(OVERHEATED, true);
                world.playSound(null, pos, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.5F, 0.8F);
                if (state.get(POWERED)) newState = newState.with(POWERED, false);
            } else if (newTemp < 18 && state.get(OVERHEATED)) newState = newState.with(OVERHEATED, false);

            if (foundExtremeCold && newTemp == 0 && !state.get(SUPERCONDUCTING)) {
                grantSuperconductivityAchievement(world, pos);
                newState = newState.with(SUPERCONDUCTING, true);
            } else if (newTemp != 0 && state.get(SUPERCONDUCTING)) newState = newState.with(SUPERCONDUCTING, false);

            world.setBlockState(pos, newState, 3);
        } else {
            boolean shouldBeSuperconducting = foundExtremeCold && currentTemp == 0;
            boolean currentlySuperconducting = state.get(SUPERCONDUCTING);
            if (shouldBeSuperconducting && !currentlySuperconducting) {
                grantSuperconductivityAchievement(world, pos);
                world.setBlockState(pos, state.with(SUPERCONDUCTING, true), 3);
            } else if (!shouldBeSuperconducting && currentlySuperconducting) world.setBlockState(pos, state.with(SUPERCONDUCTING, false), 3);
        }
    }

    private int getMeltChance(Block block) {
        if (block == Blocks.SNOW) return 36000;
        if (block == Blocks.ICE) return 54000;
        if (block == Blocks.PACKED_ICE) return 72000;
        if (block == Blocks.BLUE_ICE) return 108000;
        return 200;
    }

    private void grantSuperconductivityAchievement(World world, BlockPos pos) {
        if (world.isClient) return;
        ServerWorld serverWorld = (ServerWorld) world;
        List<ServerPlayerEntity> players = serverWorld.getPlayers(player ->
                player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 100);

        for (ServerPlayerEntity player : players) {
            net.minecraft.advancement.Advancement advancement = serverWorld.getServer().getAdvancementLoader()
                    .get(new net.minecraft.util.Identifier("magnetblocks", "superconductivity"));
            if (advancement != null) {
                net.minecraft.advancement.AdvancementProgress progress = player.getAdvancementTracker().getProgress(advancement);
                if (!progress.isDone()) {
                    for (String criterion : progress.getUnobtainedCriteria()) player.getAdvancementTracker().grantCriterion(advancement, criterion);
                }
            }
        }
    }

    public static void tickPropagation(World world) {
        if (world.isClient) return;
        Queue<PropagationTask> queue = propagationQueues.get(world);
        if (queue != null && !queue.isEmpty()) {
            for (int i = 0; i < 32 && !queue.isEmpty(); i++) {
                PropagationTask task = queue.poll();
                if (task != null) {
                    task.execute(world);
                    if (task.distance < 128) {
                        for (Direction direction : DIRECTIONS) {
                            BlockPos neighborPos = task.pos.offset(direction);
                            BlockState neighborState = world.getBlockState(neighborPos);
                            if (neighborState.getBlock() instanceof MagnetBlock) {
                                if (task instanceof PowerChangeTask powerTask) {
                                    if (shouldPropagatePower(world, neighborPos, neighborState, powerTask.powered)) addToPropagationQueue(world, new PowerChangeTask(neighborPos, powerTask.powered, task.distance + 1));
                                } else if (task instanceof PolarityChangeTask polarityTask) {
                                    if (shouldPropagatePolarity(neighborState, polarityTask.attracting)) addToPropagationQueue(world, new PolarityChangeTask(neighborPos, polarityTask.attracting, task.distance + 1));
                                }
                            }
                        }
                    }
                }
            }
            if (queue.isEmpty()) propagationQueues.remove(world);
        }
    }

    private static boolean shouldPropagatePower(World world, BlockPos pos, BlockState state, boolean newPowered) {
        boolean hasLocalPower = world.isReceivingRedstonePower(pos);
        if (hasLocalPower && !newPowered) return false;
        return state.get(POWERED) != newPowered;
    }

    private static boolean shouldPropagatePolarity(BlockState state, boolean newAttracting) {
        return state.get(ATTRACTING) != newAttracting;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        ItemStack itemStack = player.getStackInHand(hand);
        if (itemStack.getItem() == Items.IRON_AXE) {
            if (!world.isClient) {
                boolean currentPolarity = state.get(ATTRACTING);
                boolean newPolarity = !currentPolarity;
                world.setBlockState(pos, state.with(ATTRACTING, newPolarity), 2);
                world.addBlockBreakParticles(pos, state);
                addToPropagationQueue(world, new PolarityChangeTask(pos, newPolarity, 0));
                if (!player.getAbilities().creativeMode) itemStack.damage(10, player, (playerEntity) -> playerEntity.sendToolBreakStatus(hand));
                world.playSound(null, pos, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.BLOCKS, 1.0F, 1.0F);
                if (world instanceof ServerWorld serverWorld) serverWorld.spawnParticles(ParticleTypes.ELECTRIC_SPARK, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.1);
            }
            return ActionResult.SUCCESS;
        }

        if (itemStack.getItem() == Items.STICK && player.isSneaking()) {
            if (!world.isClient) {
                int temp = state.get(TEMPERATURE);
                boolean overheated = state.get(OVERHEATED);
                boolean superconducting = state.get(SUPERCONDUCTING);
                String tempStatus = getTemperatureString(temp);
                player.sendMessage(net.minecraft.text.Text.literal("§6Magnet Temperature: " + temp + "/20 (" + tempStatus + ") | Overheated: " + overheated + " | Superconducting: " + superconducting), false);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    private static @NotNull String getTemperatureString(int temp) {
        if (temp == 0) return "Absolute Zero (Superconducting)";
        else if (temp <= 2) return "Extreme Cold";
        else if (temp <= 5) return "Very Cold";
        else if (temp <= 8) return "Cold";
        else if (temp <= 12) return "Normal";
        else if (temp <= 15) return "Warm";
        else if (temp <= 17) return "Very Hot";
        else return "Extreme Heat (Overheated)";
    }

    private static void addToPropagationQueue(World world, PropagationTask task) {
        Queue<PropagationTask> queue = propagationQueues.computeIfAbsent(world, k -> new LinkedList<>());
        boolean alreadyExists = queue.stream().anyMatch(t -> t.pos.equals(task.pos) && t.getClass() == task.getClass());
        if (!alreadyExists) queue.add(task);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new MagnetBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient ? null : checkType(type, ModBlockEntities.MAGNET_BLOCK_ENTITY, (world1, pos, state1, blockEntity) -> MagnetBlockEntity.tick(world1, pos, state1));
    }

    @Nullable
    protected static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> checkType(BlockEntityType<A> givenType, BlockEntityType<E> expectedType, BlockEntityTicker<? super E> ticker) {
        return expectedType == givenType ? (BlockEntityTicker<A>) ticker : null;
    }

    private static abstract class PropagationTask {
        public final BlockPos pos;
        public final int distance;
        public PropagationTask(BlockPos pos, int distance) { this.pos = pos; this.distance = distance; }
        public abstract void execute(World world);
    }

    private static class PowerChangeTask extends PropagationTask {
        private final boolean powered;
        public PowerChangeTask(BlockPos pos, boolean powered, int distance) { super(pos, distance); this.powered = powered; }
        @Override
        public void execute(World world) {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof MagnetBlock) {
                boolean hasLocalPower = world.isReceivingRedstonePower(pos);
                if (hasLocalPower) {
                    if (!state.get(POWERED)) world.setBlockState(pos, state.with(POWERED, true), 2);
                    return;
                }
                if (state.get(POWERED) != powered) world.setBlockState(pos, state.with(POWERED, powered), 2);
            }
        }
    }

    private static class PolarityChangeTask extends PropagationTask {
        private final boolean attracting;
        public PolarityChangeTask(BlockPos pos, boolean attracting, int distance) { super(pos, distance); this.attracting = attracting; }
        @Override
        public void execute(World world) {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof MagnetBlock && state.get(ATTRACTING) != attracting) {
                world.setBlockState(pos, state.with(ATTRACTING, attracting), 2);
                world.addBlockBreakParticles(pos, state);
            }
        }
    }
}
