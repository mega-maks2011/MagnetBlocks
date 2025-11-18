package net.m998.magnetblocks;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class MagnetBlock extends Block implements BlockEntityProvider {
    public static final BooleanProperty POWERED = Properties.POWERED;
    public static final BooleanProperty ATTRACTING = BooleanProperty.of("attracting");

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Map<World, Queue<PropagationTask>> propagationQueues = new WeakHashMap<>();
    private static final ThreadLocal<Set<BlockPos>> updatingBlocks = ThreadLocal.withInitial(HashSet::new);

    protected MagnetBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState()
                .with(POWERED, false)
                .with(ATTRACTING, true));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(POWERED, ATTRACTING);
    }

    @Override
    public ItemStack getPickStack(BlockView world, BlockPos pos, BlockState state) {
        return new ItemStack(ModItems.MAGNET_ITEM);
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            if (!world.isClient && !moved) {
                // Drop magnet item when block is broken
                ItemStack magnetStack = new ItemStack(ModItems.MAGNET_ITEM);
                ItemEntity itemEntity = new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, magnetStack);
                world.spawnEntity(itemEntity);
            }

            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof MagnetBlockEntity) {
                world.removeBlockEntity(pos);
            }
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    @Override
    public void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock, BlockPos sourcePos, boolean notify) {
        if (world.isClient) return;

        // Prevent infinite recursion
        if (updatingBlocks.get().contains(pos)) {
            return;
        }

        try {
            updatingBlocks.get().add(pos);

            boolean isRedstoneUpdate = sourceBlock.getDefaultState().emitsRedstonePower() ||
                    world.getBlockState(sourcePos).getBlock().getDefaultState().emitsRedstonePower();

            if (!isRedstoneUpdate) {
                // Handle non-redstone updates (like block placement)
                boolean hasLocalPower = world.isReceivingRedstonePower(pos);
                if (hasLocalPower && !state.get(POWERED)) {
                    world.setBlockState(pos, state.with(POWERED, true), 2);
                }
                return;
            }

            // Handle redstone power changes
            boolean powered = world.isReceivingRedstonePower(pos);
            boolean currentPowered = state.get(POWERED);

            if (powered != currentPowered) {
                world.setBlockState(pos, state.with(POWERED, powered), 2);

                // Propagate power change to adjacent magnet blocks
                Set<BlockPos> visited = new HashSet<>();
                visited.add(pos);

                for (Direction direction : DIRECTIONS) {
                    BlockPos neighborPos = pos.offset(direction);
                    if (!visited.contains(neighborPos)) {
                        BlockState neighborState = world.getBlockState(neighborPos);
                        if (neighborState.getBlock() instanceof MagnetBlock) {
                            addToPropagationQueue(world, new PowerChangeTask(neighborPos, powered, 0));
                        }
                    }
                }
            }
        } finally {
            updatingBlocks.get().remove(pos);
        }
    }

    public static void tickPropagation(World world) {
        if (world.isClient) return;

        Queue<PropagationTask> queue = propagationQueues.get(world);
        if (queue != null && !queue.isEmpty()) {
            // Process up to 32 tasks per tick for performance
            for (int i = 0; i < 32 && !queue.isEmpty(); i++) {
                PropagationTask task = queue.poll();
                if (task != null) {
                    task.execute(world);

                    // Propagate to neighbors if within range
                    if (task.distance < 128) {
                        for (Direction direction : DIRECTIONS) {
                            BlockPos neighborPos = task.pos.offset(direction);
                            BlockState neighborState = world.getBlockState(neighborPos);
                            if (neighborState.getBlock() instanceof MagnetBlock) {
                                if (task instanceof PowerChangeTask powerTask) {
                                    if (shouldPropagatePower(world, neighborPos, neighborState, powerTask.powered)) {
                                        addToPropagationQueue(world,
                                                new PowerChangeTask(neighborPos, powerTask.powered, task.distance + 1));
                                    }
                                }
                                else if (task instanceof PolarityChangeTask polarityTask) {
                                    if (shouldPropagatePolarity(neighborState, polarityTask.attracting)) {
                                        addToPropagationQueue(world,
                                                new PolarityChangeTask(neighborPos, polarityTask.attracting, task.distance + 1));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (queue.isEmpty()) {
                propagationQueues.remove(world);
            }
        }
    }

    private static boolean shouldPropagatePower(World world, BlockPos pos, BlockState state, boolean newPowered) {
        // Don't override local redstone power
        boolean hasLocalPower = world.isReceivingRedstonePower(pos);
        if (hasLocalPower && !newPowered) {
            return false;
        }
        return state.get(POWERED) != newPowered;
    }

    private static boolean shouldPropagatePolarity(BlockState state, boolean newAttracting) {
        return state.get(ATTRACTING) != newAttracting;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        ItemStack itemStack = player.getStackInHand(hand);

        // Use iron axe to toggle polarity
        if (itemStack.getItem() == Items.IRON_AXE) {
            if (!world.isClient) {
                boolean currentPolarity = state.get(ATTRACTING);
                boolean newPolarity = !currentPolarity;

                world.setBlockState(pos, state.with(ATTRACTING, newPolarity), 2);
                world.addBlockBreakParticles(pos, state);

                // Propagate polarity change to adjacent magnets
                addToPropagationQueue(world, new PolarityChangeTask(pos, newPolarity, 0));

                // Damage the axe
                if (!player.getAbilities().creativeMode) {
                    itemStack.damage(10, player, (playerEntity) -> playerEntity.sendToolBreakStatus(hand));
                }

                world.playSound(null, pos, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE,
                        SoundCategory.BLOCKS, 1.0F, 1.0F);

                // Spawn electric particles
                if (world instanceof ServerWorld serverWorld) {
                    serverWorld.spawnParticles(ParticleTypes.ELECTRIC_SPARK,
                            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                            10, 0.3, 0.3, 0.3, 0.1);
                }
            }
            return ActionResult.SUCCESS;
        }

        return ActionResult.PASS;
    }

    private static void addToPropagationQueue(World world, PropagationTask task) {
        Queue<PropagationTask> queue = propagationQueues.computeIfAbsent(world, k -> new LinkedList<>());
        // Avoid duplicate tasks for same position and type
        boolean alreadyExists = queue.stream().anyMatch(t -> t.pos.equals(task.pos) && t.getClass() == task.getClass());
        if (!alreadyExists) {
            queue.add(task);
        }
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

        public PropagationTask(BlockPos pos, int distance) {
            this.pos = pos;
            this.distance = distance;
        }

        public abstract void execute(World world);
    }

    private static class PowerChangeTask extends PropagationTask {
        private final boolean powered;

        public PowerChangeTask(BlockPos pos, boolean powered, int distance) {
            super(pos, distance);
            this.powered = powered;
        }

        @Override
        public void execute(World world) {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof MagnetBlock) {
                boolean hasLocalPower = world.isReceivingRedstonePower(pos);

                // Respect local redstone power
                if (hasLocalPower) {
                    if (!state.get(POWERED)) {
                        world.setBlockState(pos, state.with(POWERED, true), 2);
                    }
                    return;
                }

                if (state.get(POWERED) != powered) {
                    world.setBlockState(pos, state.with(POWERED, powered), 2);
                }
            }
        }
    }

    private static class PolarityChangeTask extends PropagationTask {
        private final boolean attracting;

        public PolarityChangeTask(BlockPos pos, boolean attracting, int distance) {
            super(pos, distance);
            this.attracting = attracting;
        }

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