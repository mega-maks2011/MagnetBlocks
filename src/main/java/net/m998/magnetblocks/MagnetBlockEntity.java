package net.m998.magnetblocks;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.*;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MagnetBlockEntity extends BlockEntity {
    private static final double RANGE = 40.0;
    private static final double FORCE = 0.001;
    private static final boolean AFFECT_HOSTILE_MOBS = false;
    private static final boolean AFFECT_PASSIVE_ANIMALS = false;
    private static final boolean AFFECT_VILLAGERS = false;
    private static final boolean AFFECT_OTHER_ENTITIES = false;
    private static final Map<Item, Double> ITEM_STRENGTH_MAP = new HashMap<>();

    static {
        initializeItemStrengthMap();
    }

    public MagnetBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MAGNET_BLOCK_ENTITY, pos, state);
    }

    public static void tick(World world, BlockPos pos, BlockState state) {
        if (world.isClient) return;
        if (!state.get(MagnetBlock.POWERED)) {
            processMagnetBlock(world, pos, state);
        }
        processPhantomMagnets(world);
    }

    private static void processMagnetBlock(World world, BlockPos pos, BlockState state) {
        Vec3d blockCenter = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        boolean isAttracting = state.get(MagnetBlock.ATTRACTING);
        applyMagneticForce(world, blockCenter, RANGE, FORCE, isAttracting);
    }

    private static void processPhantomMagnets(World world) {
        if (world instanceof ServerWorld serverWorld) {
            MinecraftServer server = serverWorld.getServer();
            PhantomMagnetManager manager = PhantomMagnetManager.get(server);
            for (PhantomMagnetManager.PhantomMagnet magnet : manager.getMagnets().values()) {
                Vec3d magnetCenter = new Vec3d(magnet.getPos().getX() + 0.5, magnet.getPos().getY() + 0.5, magnet.getPos().getZ() + 0.5);
                double force = FORCE * magnet.getForceMultiplier();
                applyMagneticForce(world, magnetCenter, magnet.getRadius(), force, magnet.isAttracting());
            }
        }
    }

    private static void applyMagneticForce(World world, Vec3d center, double range, double baseForce, boolean isAttracting) {
        Box area = new Box(center.x - range, center.y - range, center.z - range, center.x + range, center.y + range, center.z + range);
        List<Entity> entities = world.getNonSpectatingEntities(Entity.class, area);

        for (Entity entity : entities) {
            double strength = getEntityStrength(entity);
            if (strength <= 0) continue;

            Vec3d entityPos = entity.getPos();
            double distance = entityPos.distanceTo(center);
            if (distance <= range && distance > 1.5) {
                Vec3d direction = isAttracting ? center.subtract(entityPos).normalize() : entityPos.subtract(center).normalize();
                double forceMultiplier = 1.0 - (distance / range);
                double strengthMultiplier = Math.max(0.01, Math.min(strength, 5.0));
                Vec3d velocity = direction.multiply(baseForce * forceMultiplier * strengthMultiplier);

                entity.addVelocity(velocity.x, velocity.y, velocity.z);
                entity.velocityModified = true;

                if (world.random.nextInt(10) == 0) {
                    if (isAttracting) {
                        world.addParticle(ParticleTypes.ELECTRIC_SPARK, entityPos.x, entityPos.y + entity.getHeight() / 2, entityPos.z, velocity.x * 0.1, velocity.y * 0.1, velocity.z * 0.1);
                    } else {
                        world.addParticle(ParticleTypes.SOUL_FIRE_FLAME, entityPos.x, entityPos.y + entity.getHeight() / 2, entityPos.z, velocity.x * 0.1, velocity.y * 0.1, velocity.z * 0.1);
                    }
                }
            }
        }
    }

    private static double getEntityStrength(Entity entity) {
        if (entity instanceof IronGolemEntity) return 3.0;
        if (entity instanceof EnderPearlEntity) return 1.5;
        if (entity.getType().toString().contains("falling") || entity.getClass().getSimpleName().toLowerCase().contains("falling")) {
            try {
                Class<?> entityClass = entity.getClass();
                java.lang.reflect.Field blockField = null;
                for (java.lang.reflect.Field field : entityClass.getDeclaredFields()) {
                    if (field.getType().getSimpleName().contains("Block") || field.getType().getSimpleName().contains("BlockState")) {
                        blockField = field;
                        break;
                    }
                }
                if (blockField != null) {
                    blockField.setAccessible(true);
                    Object blockData = blockField.get(entity);
                    String blockString = blockData.toString().toLowerCase();
                    if (blockString.contains("anvil") || blockString.contains("iron") || blockString.contains("netherite") || blockString.contains("chain") || blockString.contains("cauldron") || blockString.contains("hopper")) {
                        return 4.0;
                    }
                }
            } catch (Exception e) {
                String entityString = entity.toString().toLowerCase();
                if (entityString.contains("anvil") || entityString.contains("iron")) return 4.0;
            }
            return 0.0;
        }
        if (entity instanceof ItemEntity itemEntity) return getItemStrength(itemEntity.getStack());
        if (entity instanceof PlayerEntity player) {
            if (player.isCreative() || player.isSpectator()) return 0.0;
            return getPlayerStrength(player);
        }
        if (entity instanceof LivingEntity living) {
            if (living instanceof Monster) return AFFECT_HOSTILE_MOBS ? 1.0 : 0.0;
            if (living instanceof AnimalEntity) return AFFECT_PASSIVE_ANIMALS ? 1.0 : 0.0;
            if (living instanceof VillagerEntity) return AFFECT_VILLAGERS ? 1.0 : 0.0;
            return AFFECT_OTHER_ENTITIES ? 1.0 : 0.0;
        }
        return 0.0;
    }

    private static double getPlayerStrength(PlayerEntity player) {
        double armorStrength = getArmorStrength(player);
        double heldItemStrength = getHeldItemStrength(player);
        return Math.max(armorStrength, heldItemStrength);
    }

    private static double getArmorStrength(PlayerEntity player) {
        double totalStrength = 0.0;
        int armorPieces = 0;
        for (ItemStack armorStack : player.getArmorItems()) {
            double pieceStrength = getItemStrength(armorStack);
            if (pieceStrength > 0) {
                totalStrength += pieceStrength;
                armorPieces++;
            }
        }
        return armorPieces >= 2 ? totalStrength / armorPieces : 0.0;
    }

    private static double getHeldItemStrength(PlayerEntity player) {
        return Math.max(getItemStrength(player.getMainHandStack()), getItemStrength(player.getOffHandStack()));
    }

    private static double getItemStrength(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;
        if (stack.getItem() == ModItems.MAGNET_ITEM) return 5.0;
        Double strength = ITEM_STRENGTH_MAP.get(stack.getItem());
        return strength != null ? strength : 0.0;
    }

    private static void initializeItemStrengthMap() {
        ITEM_STRENGTH_MAP.put(Items.ANVIL, 4.0);
        ITEM_STRENGTH_MAP.put(Items.CHIPPED_ANVIL, 3.5);
        ITEM_STRENGTH_MAP.put(Items.DAMAGED_ANVIL, 3.0);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_BLOCK, 5.0);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_INGOT, 3.0);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_SWORD, 3.5);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_AXE, 3.5);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_PICKAXE, 3.5);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_SHOVEL, 3.0);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_HOE, 3.0);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_HELMET, 3.0);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_CHESTPLATE, 4.0);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_LEGGINGS, 3.5);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_BOOTS, 3.0);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_SCRAP, 3.0);
        ITEM_STRENGTH_MAP.put(Items.ANCIENT_DEBRIS, 2.5);
        ITEM_STRENGTH_MAP.put(Items.ENDER_PEARL, 1.0);
        ITEM_STRENGTH_MAP.put(Items.ENDER_EYE, 1.2);
        ITEM_STRENGTH_MAP.put(Items.IRON_BLOCK, 3.0);
        ITEM_STRENGTH_MAP.put(Items.RAW_IRON_BLOCK, 2.5);
        ITEM_STRENGTH_MAP.put(Items.IRON_DOOR, 2.0);
        ITEM_STRENGTH_MAP.put(Items.IRON_TRAPDOOR, 1.5);
        ITEM_STRENGTH_MAP.put(Items.IRON_BARS, 1.0);
        ITEM_STRENGTH_MAP.put(Items.CHAIN, 0.8);
        ITEM_STRENGTH_MAP.put(Items.HOPPER, 2.5);
        ITEM_STRENGTH_MAP.put(Items.CAULDRON, 2.0);
        ITEM_STRENGTH_MAP.put(Items.BUCKET, 1.0);
        ITEM_STRENGTH_MAP.put(Items.MINECART, 2.5);
        ITEM_STRENGTH_MAP.put(Items.RAIL, 0.5);
        ITEM_STRENGTH_MAP.put(Items.POWERED_RAIL, 1.0);
        ITEM_STRENGTH_MAP.put(Items.DETECTOR_RAIL, 1.0);
        ITEM_STRENGTH_MAP.put(Items.ACTIVATOR_RAIL, 1.0);
        ITEM_STRENGTH_MAP.put(Items.IRON_SWORD, 2.0);
        ITEM_STRENGTH_MAP.put(Items.IRON_AXE, 2.0);
        ITEM_STRENGTH_MAP.put(Items.IRON_PICKAXE, 2.0);
        ITEM_STRENGTH_MAP.put(Items.IRON_SHOVEL, 1.5);
        ITEM_STRENGTH_MAP.put(Items.IRON_HOE, 1.5);
        ITEM_STRENGTH_MAP.put(Items.SHEARS, 1.0);
        ITEM_STRENGTH_MAP.put(Items.FLINT_AND_STEEL, 0.3);
        ITEM_STRENGTH_MAP.put(Items.IRON_HELMET, 1.5);
        ITEM_STRENGTH_MAP.put(Items.IRON_CHESTPLATE, 2.5);
        ITEM_STRENGTH_MAP.put(Items.IRON_LEGGINGS, 2.0);
        ITEM_STRENGTH_MAP.put(Items.IRON_BOOTS, 1.5);
        ITEM_STRENGTH_MAP.put(Items.IRON_HORSE_ARMOR, 2.0);
        ITEM_STRENGTH_MAP.put(Items.IRON_INGOT, 1.0);
        ITEM_STRENGTH_MAP.put(Items.RAW_IRON, 0.8);
        ITEM_STRENGTH_MAP.put(Items.IRON_NUGGET, 0.3);
        ITEM_STRENGTH_MAP.put(Items.IRON_ORE, 0.8);
        ITEM_STRENGTH_MAP.put(Items.DEEPSLATE_IRON_ORE, 0.8);
        ITEM_STRENGTH_MAP.put(Items.COMPASS, 0.7);
        ITEM_STRENGTH_MAP.put(Items.PISTON, 1.5);
        ITEM_STRENGTH_MAP.put(Items.STICKY_PISTON, 1.5);
        ITEM_STRENGTH_MAP.put(Items.TRIPWIRE_HOOK, 0.5);
        ITEM_STRENGTH_MAP.put(Items.CHEST_MINECART, 2.5);
        ITEM_STRENGTH_MAP.put(Items.FURNACE_MINECART, 3.0);
        ITEM_STRENGTH_MAP.put(Items.TNT_MINECART, 2.5);
        ITEM_STRENGTH_MAP.put(Items.HOPPER_MINECART, 3.0);
        ITEM_STRENGTH_MAP.put(Items.SMITHING_TABLE, 2.0);
        ITEM_STRENGTH_MAP.put(Items.IRON_GOLEM_SPAWN_EGG, 1.0);
        ITEM_STRENGTH_MAP.put(Items.HEAVY_WEIGHTED_PRESSURE_PLATE, 1.8);
        ITEM_STRENGTH_MAP.put(Items.LAVA_BUCKET, 1.3);
        ITEM_STRENGTH_MAP.put(Items.WATER_BUCKET, 1.1);
        ITEM_STRENGTH_MAP.put(Items.MILK_BUCKET, 1.1);
        ITEM_STRENGTH_MAP.put(Items.POWDER_SNOW_BUCKET, 1.1);
        ITEM_STRENGTH_MAP.put(Items.AXOLOTL_BUCKET, 1.1);
        ITEM_STRENGTH_MAP.put(Items.COD_BUCKET, 1.1);
        ITEM_STRENGTH_MAP.put(Items.SALMON_BUCKET, 1.1);
        ITEM_STRENGTH_MAP.put(Items.TROPICAL_FISH_BUCKET, 1.1);
        ITEM_STRENGTH_MAP.put(Items.PUFFERFISH_BUCKET, 1.1);
        ITEM_STRENGTH_MAP.put(Items.TADPOLE_BUCKET, 1.1);
        ITEM_STRENGTH_MAP.put(Items.LANTERN, 0.9);
        ITEM_STRENGTH_MAP.put(Items.SOUL_LANTERN, 0.9);
        ITEM_STRENGTH_MAP.put(Items.SHIELD, 1.8);
        ITEM_STRENGTH_MAP.put(Items.SADDLE, 0.8);
        ITEM_STRENGTH_MAP.put(Items.REDSTONE, 0.1);
        ITEM_STRENGTH_MAP.put(Items.REDSTONE_BLOCK, 0.3);
        ITEM_STRENGTH_MAP.put(Items.REPEATER, 0.5);
        ITEM_STRENGTH_MAP.put(Items.COMPARATOR, 0.5);
        ITEM_STRENGTH_MAP.put(Items.OBSERVER, 1.0);
        ITEM_STRENGTH_MAP.put(Items.LODESTONE, 4.0);
    }
}
