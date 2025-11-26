package net.m998.magnetblocks;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MagnetBlockEntity extends BlockEntity {
    private static final double RANGE = 40.0;
    private static final double FORCE = 0.01;
    private static final double PHANTOM_BASE_FORCE = 0.04;
    private static final Map<Item, Double> ITEM_STRENGTH_MAP = new HashMap<>();
    static { initializeItemStrengthMap(); }

    public MagnetBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MAGNET_BLOCK_ENTITY, pos, state);
    }

    public static void tick(World world, BlockPos pos, BlockState state) {
        if (world.isClient) return;
        if (!state.get(MagnetBlock.POWERED)) processMagnetBlock(world, pos, state);
    }

    private static void processMagnetBlock(World world, BlockPos pos, BlockState state) {
        if (state.get(MagnetBlock.OVERHEATED)) {
            if (world.getTime() % 10 == 0) {
                world.addParticle(ParticleTypes.LAVA, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 0, 0.1, 0);
                world.addParticle(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 0, 0.1, 0);
            }
            return;
        }

        Vec3d blockCenter = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        boolean isAttracting = state.get(MagnetBlock.ATTRACTING);
        double temperatureMultiplier = calculateTemperatureMultiplier(state);
        double adjustedForce = FORCE * temperatureMultiplier;
        double adjustedRange = RANGE * temperatureMultiplier;

        if (world.getTime() % 20 == 0) playMagnetSound(world, pos, state);
        applyMagneticForceToArea(world, blockCenter, adjustedRange, adjustedForce, isAttracting, false);
    }

    private static double calculateTemperatureMultiplier(BlockState state) {
        int temperature = state.get(MagnetBlock.TEMPERATURE);
        double multiplier = 1.0;

        if (temperature < 10) {
            double coolingBonus = (10 - temperature) * 0.1;
            multiplier = 1.0 + coolingBonus;
        } else if (temperature > 10) {
            double heatingPenalty = (temperature - 10) * 0.08;
            multiplier = 1.0 - heatingPenalty;
        }

        if (state.get(MagnetBlock.SUPERCONDUCTING)) multiplier *= 2.0;
        return multiplier;
    }

    private static void playMagnetSound(World world, BlockPos pos, BlockState state) {
        float volume = 0.3F;
        float pitch = 0.9F;

        if (state.get(MagnetBlock.SUPERCONDUCTING)) {
            pitch = 1.4F;
            volume = 0.5F;
        } else {
            int temperature = state.get(MagnetBlock.TEMPERATURE);
            if (temperature < 10) pitch = 1.1F + (10 - temperature) * 0.05F;
            else if (temperature > 10) pitch = 0.8F - (temperature - 10) * 0.03F;
        }

        world.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.BLOCKS, volume, pitch);
    }

    public static void processAllPhantomMagnets(World world) {
        if (world.isClient) return;
        if (world instanceof ServerWorld serverWorld) {
            PhantomMagnetManager manager = PhantomMagnetManager.get(serverWorld.getServer());
            var magnets = manager.getMagnets();

            for (var entry : magnets.entrySet()) {
                PhantomMagnetManager.PhantomMagnet magnet = entry.getValue();
                Vec3d magnetCenter = new Vec3d(magnet.getPos().getX() + 0.5, magnet.getPos().getY() + 0.5, magnet.getPos().getZ() + 0.5);
                double force = PHANTOM_BASE_FORCE * magnet.getForceMultiplier();

                if (world.getTime() % 20 == 0) {
                    world.playSound(null, magnetCenter.x, magnetCenter.y, magnetCenter.z,
                            SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.BLOCKS, 0.4F, 0.7F);
                }

                applyMagneticForceToArea(world, magnetCenter, magnet.getRadius(), force, magnet.isAttracting(), true);
            }
        }
    }

    private static void applyMagneticForceToArea(World world, Vec3d center, double range, double baseForce, boolean isAttracting, boolean isPhantom) {
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

                if (world.random.nextInt(isPhantom ? 3 : 10) == 0) spawnMagneticParticles(world, entityPos, entity.getHeight(), velocity, isAttracting, isPhantom);
            }
        }
    }

    private static void spawnMagneticParticles(World world, Vec3d pos, float height, Vec3d velocity, boolean isAttracting, boolean isPhantom) {
        if (isAttracting) {
            if (isPhantom) world.addParticle(ParticleTypes.SOUL_FIRE_FLAME, pos.x, pos.y + height / 2, pos.z, velocity.x * 0.1, velocity.y * 0.1, velocity.z * 0.1);
            else world.addParticle(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y + height / 2, pos.z, velocity.x * 0.1, velocity.y * 0.1, velocity.z * 0.1);
        } else {
            if (isPhantom) world.addParticle(ParticleTypes.REVERSE_PORTAL, pos.x, pos.y + height / 2, pos.z, velocity.x * 0.1, velocity.y * 0.1, velocity.z * 0.1);
            else world.addParticle(ParticleTypes.SOUL_FIRE_FLAME, pos.x, pos.y + height / 2, pos.z, velocity.x * 0.1, velocity.y * 0.1, velocity.z * 0.1);
        }
    }

    private static double getEntityStrength(Entity entity) {
        if (entity instanceof PlayerEntity player) {
            if (player.isCreative() || player.isSpectator()) return 0.0;
            MinecraftServer server = entity.getWorld().getServer();
            if (server != null) {
                MagnetWhitelistManager whitelistManager = MagnetWhitelistManager.get(server);
                Double whitelistStrength = whitelistManager.getPlayerStrength(player.getUuid());
                if (whitelistStrength != null) return whitelistStrength;
            }
            return getPlayerStrength(player);
        }
        if (entity instanceof IronGolemEntity) return 2.5;
        if (entity instanceof EndermanEntity) return 0.5;
        if (entity instanceof LivingEntity living) {
            double equipmentStrength = getMobEquipmentStrength(living);
            if (equipmentStrength > 0) return equipmentStrength;
        }
        if (entity instanceof EnderPearlEntity) return 2.5;
        if (entity instanceof FallingBlockEntity fallingBlock) return getFallingBlockStrength(fallingBlock);
        if (entity instanceof ItemEntity itemEntity) return getItemStrength(itemEntity.getStack());
        return 0.0;
    }

    private static double getFallingBlockStrength(FallingBlockEntity fallingBlock) {
        BlockState blockState = fallingBlock.getBlockState();
        String blockName = blockState.getBlock().getTranslationKey().toLowerCase();
        return blockName.contains("anvil") ? 3.0 : 0.0;
    }

    private static double getMobEquipmentStrength(LivingEntity mob) {
        double totalStrength = 0.0;
        int magneticItems = 0;
        for (ItemStack stack : mob.getArmorItems()) {
            double strength = getItemStrength(stack);
            if (strength > 0) { totalStrength += strength; magneticItems++; }
        }
        ItemStack mainHand = mob.getMainHandStack();
        double mainHandStrength = getItemStrength(mainHand);
        if (mainHandStrength > 0) { totalStrength += mainHandStrength; magneticItems++; }
        return magneticItems > 0 ? totalStrength / magneticItems : 0.0;
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
            if (pieceStrength > 0) { totalStrength += pieceStrength; armorPieces++; }
        }
        return armorPieces >= 2 ? totalStrength / armorPieces : 0.0;
    }

    private static double getHeldItemStrength(PlayerEntity player) {
        return Math.max(getItemStrength(player.getMainHandStack()), getItemStrength(player.getOffHandStack()));
    }

    private static double getItemStrength(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;
        if (stack.getItem() == ModItems.MAGNET_ITEM) return 4.5;
        Double strength = ITEM_STRENGTH_MAP.get(stack.getItem());
        return strength != null ? strength : 0.0;
    }

    private static void initializeItemStrengthMap() {
        // Netherite items
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_BLOCK, 1.2);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_INGOT, 0.9);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_SWORD, 1.08);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_AXE, 1.08);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_PICKAXE, 1.08);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_SHOVEL, 0.9);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_HOE, 0.9);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_HELMET, 0.9);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_CHESTPLATE, 1.2);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_LEGGINGS, 1.08);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_BOOTS, 0.9);
        ITEM_STRENGTH_MAP.put(Items.NETHERITE_SCRAP, 0.72);
        ITEM_STRENGTH_MAP.put(Items.ANCIENT_DEBRIS, 0.6);

        // Iron blocks and items
        ITEM_STRENGTH_MAP.put(Items.IRON_BLOCK, 1.8);
        ITEM_STRENGTH_MAP.put(Items.RAW_IRON_BLOCK, 1.5);
        ITEM_STRENGTH_MAP.put(Items.IRON_DOOR, 1.2);
        ITEM_STRENGTH_MAP.put(Items.IRON_TRAPDOOR, 1.08);
        ITEM_STRENGTH_MAP.put(Items.IRON_BARS, 0.9);
        ITEM_STRENGTH_MAP.put(Items.CHAIN, 0.72);
        ITEM_STRENGTH_MAP.put(Items.HOPPER, 1.68);
        ITEM_STRENGTH_MAP.put(Items.CAULDRON, 1.2);

        // Iron tools and weapons
        ITEM_STRENGTH_MAP.put(Items.IRON_SWORD, 1.08);
        ITEM_STRENGTH_MAP.put(Items.IRON_AXE, 1.08);
        ITEM_STRENGTH_MAP.put(Items.IRON_PICKAXE, 1.08);
        ITEM_STRENGTH_MAP.put(Items.IRON_SHOVEL, 0.9);
        ITEM_STRENGTH_MAP.put(Items.IRON_HOE, 0.9);
        ITEM_STRENGTH_MAP.put(Items.SHEARS, 0.72);
        ITEM_STRENGTH_MAP.put(Items.FLINT_AND_STEEL, 0.48);

        // Iron armor
        ITEM_STRENGTH_MAP.put(Items.IRON_HELMET, 0.9);
        ITEM_STRENGTH_MAP.put(Items.IRON_CHESTPLATE, 1.5);
        ITEM_STRENGTH_MAP.put(Items.IRON_LEGGINGS, 1.2);
        ITEM_STRENGTH_MAP.put(Items.IRON_BOOTS, 0.9);
        ITEM_STRENGTH_MAP.put(Items.IRON_HORSE_ARMOR, 1.2);

        // Iron materials
        ITEM_STRENGTH_MAP.put(Items.IRON_INGOT, 0.6);
        ITEM_STRENGTH_MAP.put(Items.RAW_IRON, 0.48);
        ITEM_STRENGTH_MAP.put(Items.IRON_NUGGET, 0.18);
        ITEM_STRENGTH_MAP.put(Items.IRON_ORE, 0.48);
        ITEM_STRENGTH_MAP.put(Items.DEEPSLATE_IRON_ORE, 0.48);

        // Special blocks and items
        ITEM_STRENGTH_MAP.put(Items.ANVIL, 1.5);
        ITEM_STRENGTH_MAP.put(Items.CHIPPED_ANVIL, 1.0);
        ITEM_STRENGTH_MAP.put(Items.DAMAGED_ANVIL, 0.7);
        ITEM_STRENGTH_MAP.put(Items.BUCKET, 0.6);
        ITEM_STRENGTH_MAP.put(Items.MINECART, 1.5);
        ITEM_STRENGTH_MAP.put(Items.RAIL, 0.36);
        ITEM_STRENGTH_MAP.put(Items.POWERED_RAIL, 0.48);
        ITEM_STRENGTH_MAP.put(Items.DETECTOR_RAIL, 0.48);
        ITEM_STRENGTH_MAP.put(Items.ACTIVATOR_RAIL, 0.48);
        ITEM_STRENGTH_MAP.put(Items.COMPASS, 0.3);
        ITEM_STRENGTH_MAP.put(Items.PISTON, 0.9);
        ITEM_STRENGTH_MAP.put(Items.STICKY_PISTON, 0.9);
        ITEM_STRENGTH_MAP.put(Items.TRIPWIRE_HOOK, 0.24);
        ITEM_STRENGTH_MAP.put(Items.CHEST_MINECART, 1.68);
        ITEM_STRENGTH_MAP.put(Items.FURNACE_MINECART, 1.8);
        ITEM_STRENGTH_MAP.put(Items.TNT_MINECART, 1.68);
        ITEM_STRENGTH_MAP.put(Items.HOPPER_MINECART, 1.8);
        ITEM_STRENGTH_MAP.put(Items.SMITHING_TABLE, 0.9);
        ITEM_STRENGTH_MAP.put(Items.IRON_GOLEM_SPAWN_EGG, 0.42);
        ITEM_STRENGTH_MAP.put(Items.HEAVY_WEIGHTED_PRESSURE_PLATE, 1.08);

        // Buckets with content
        ITEM_STRENGTH_MAP.put(Items.LAVA_BUCKET, 0.72);
        ITEM_STRENGTH_MAP.put(Items.WATER_BUCKET, 0.6);
        ITEM_STRENGTH_MAP.put(Items.MILK_BUCKET, 0.6);
        ITEM_STRENGTH_MAP.put(Items.POWDER_SNOW_BUCKET, 0.6);
        ITEM_STRENGTH_MAP.put(Items.AXOLOTL_BUCKET, 0.6);
        ITEM_STRENGTH_MAP.put(Items.COD_BUCKET, 0.6);
        ITEM_STRENGTH_MAP.put(Items.SALMON_BUCKET, 0.6);
        ITEM_STRENGTH_MAP.put(Items.TROPICAL_FISH_BUCKET, 0.6);
        ITEM_STRENGTH_MAP.put(Items.PUFFERFISH_BUCKET, 0.6);
        ITEM_STRENGTH_MAP.put(Items.TADPOLE_BUCKET, 0.6);

        // Miscellaneous
        ITEM_STRENGTH_MAP.put(Items.LANTERN, 0.36);
        ITEM_STRENGTH_MAP.put(Items.SOUL_LANTERN, 0.36);
        ITEM_STRENGTH_MAP.put(Items.SHIELD, 0.3);
        ITEM_STRENGTH_MAP.put(Items.SADDLE, 0.18);
        ITEM_STRENGTH_MAP.put(Items.LODESTONE, 2.4);

        // Chainmail armor
        ITEM_STRENGTH_MAP.put(Items.CHAINMAIL_HELMET, 0.72);
        ITEM_STRENGTH_MAP.put(Items.CHAINMAIL_CHESTPLATE, 1.08);
        ITEM_STRENGTH_MAP.put(Items.CHAINMAIL_LEGGINGS, 0.9);
        ITEM_STRENGTH_MAP.put(Items.CHAINMAIL_BOOTS, 0.72);

        // Redstone and technical items
        ITEM_STRENGTH_MAP.put(Items.REDSTONE, 0.03);
        ITEM_STRENGTH_MAP.put(Items.REDSTONE_BLOCK, 0.06);
        ITEM_STRENGTH_MAP.put(Items.REPEATER, 0.12);
        ITEM_STRENGTH_MAP.put(Items.COMPARATOR, 0.12);
        ITEM_STRENGTH_MAP.put(Items.OBSERVER, 0.6);
        ITEM_STRENGTH_MAP.put(Items.ENDER_PEARL, 1.0);
        ITEM_STRENGTH_MAP.put(Items.ENDER_EYE, 1.0);
        ITEM_STRENGTH_MAP.put(Items.BLAST_FURNACE, 0.9);
        ITEM_STRENGTH_MAP.put(Items.DISPENSER, 0.48);
        ITEM_STRENGTH_MAP.put(Items.DROPPER, 0.48);
        ITEM_STRENGTH_MAP.put(Items.CLOCK, 0.12);
    }
}
