package net.m998.magnetblocks;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import java.util.*;

public class MagneticStormManager extends PersistentState {
    private static final String DATA_NAME = "magnetblocks_storms";
    private long lastStormEndTime = 0;
    private long nextStormTime = 0;
    private boolean stormActive = false;
    private long stormEndTime = 0;
    private StormEffect currentEffect = null;
    private double stormIntensity = 1.0;
    private boolean isCustomStorm = false;
    private final Map<Integer, MagnetBackup> magnetBackups = new HashMap<>();
    private final Random random = new Random();

    public MagneticStormManager() {
        super();
        scheduleNextStorm();
    }

    public static MagneticStormManager get(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        return world.getPersistentStateManager().getOrCreate(MagneticStormManager::fromNbt, MagneticStormManager::new, DATA_NAME);
    }

    public void tick(MinecraftServer server) {
        if (!MagnetStorms.ENABLE_MAGNETIC_STORMS) return;
        long currentTime = System.currentTimeMillis();
        if (stormActive) {
            if (currentTime >= stormEndTime) endStorm(server);
        } else {
            if (!isCustomStorm && currentTime >= nextStormTime) startStorm(server);
        }
    }

    public void startStorm(MinecraftServer server) {
        if (stormActive) return;
        saveMagnetBackups(server);
        stormActive = true;
        isCustomStorm = false;
        stormIntensity = 1.0;
        currentEffect = getRandomStormEffect();
        int durationMinutes = MagnetStorms.MIN_STORM_DURATION + random.nextInt(MagnetStorms.MAX_STORM_DURATION - MagnetStorms.MIN_STORM_DURATION + 1);
        stormEndTime = System.currentTimeMillis() + (durationMinutes * 60L * 1000L);
        applyStormEffect(server);
        this.markDirty();
        System.out.println("Magnetic storm started! Effect: " + currentEffect + ", Duration: " + durationMinutes + "min");
    }

    public void startCustomStorm(MinecraftServer server, int durationMinutes, double intensity) {
        if (stormActive) return;
        saveMagnetBackups(server);
        stormActive = true;
        isCustomStorm = true;
        stormIntensity = Math.min(intensity, 5.0);
        currentEffect = getRandomStormEffect();
        stormEndTime = System.currentTimeMillis() + (durationMinutes * 60L * 1000L);
        applyStormEffect(server);
        this.markDirty();
        System.out.println("Custom magnetic storm started! Effect: " + currentEffect + ", Duration: " + durationMinutes + "min, Intensity: " + intensity);
    }

    public void endStorm(MinecraftServer server) {
        if (!stormActive) return;
        restoreMagnetBackups(server);
        stormActive = false;
        stormIntensity = 1.0;
        lastStormEndTime = System.currentTimeMillis();
        magnetBackups.clear();
        if (!isCustomStorm) scheduleNextStorm();
        isCustomStorm = false;
        this.markDirty();
        System.out.println("Magnetic storm ended. Magnets restored to original settings.");
    }

    private void saveMagnetBackups(MinecraftServer server) {
        PhantomMagnetManager magnetManager = PhantomMagnetManager.get(server);
        var magnets = magnetManager.getMagnets();
        magnetBackups.clear();
        for (var entry : magnets.entrySet()) {
            int magnetId = entry.getKey();
            PhantomMagnetManager.PhantomMagnet magnet = entry.getValue();
            MagnetBackup backup = new MagnetBackup(magnet.getRadius(), magnet.getForceMultiplier(), magnet.isAttracting());
            magnetBackups.put(magnetId, backup);
        }
        System.out.println("Saved backups for " + magnetBackups.size() + " magnets");
    }

    private void restoreMagnetBackups(MinecraftServer server) {
        PhantomMagnetManager magnetManager = PhantomMagnetManager.get(server);
        var magnets = magnetManager.getMagnets();
        int restoredCount = 0;
        for (var entry : magnets.entrySet()) {
            int magnetId = entry.getKey();
            PhantomMagnetManager.PhantomMagnet magnet = entry.getValue();
            MagnetBackup backup = magnetBackups.get(magnetId);
            if (backup != null) {
                magnet.setRadius(backup.radius);
                magnet.setForceMultiplier(backup.forceMultiplier);
                magnet.setAttracting(backup.attracting);
                restoredCount++;
            }
        }
        magnetManager.markDirty();
        System.out.println("Restored " + restoredCount + " magnets to original settings");
    }

    private void scheduleNextStorm() {
        int intervalMinutes = MagnetStorms.MIN_STORM_INTERVAL + random.nextInt(MagnetStorms.MAX_STORM_INTERVAL - MagnetStorms.MIN_STORM_INTERVAL + 1);
        nextStormTime = System.currentTimeMillis() + (intervalMinutes * 60L * 1000L);
        System.out.println("Next magnetic storm in " + intervalMinutes + " minutes");
    }

    private StormEffect getRandomStormEffect() {
        List<StormEffect> availableEffects = new ArrayList<>();
        if (MagnetStorms.STORM_POLARITY_INVERT) availableEffects.add(StormEffect.POLARITY_INVERT);
        if (MagnetStorms.STORM_RANDOM_POLARITY) availableEffects.add(StormEffect.RANDOM_POLARITY);
        if (MagnetStorms.STORM_POWER_OFF) availableEffects.add(StormEffect.POWER_OFF);
        if (MagnetStorms.STORM_POWER_BOOST) availableEffects.add(StormEffect.POWER_BOOST);
        if (MagnetStorms.STORM_POWER_REDUCE) availableEffects.add(StormEffect.POWER_REDUCE);
        if (MagnetStorms.STORM_RANDOM_RADIUS) availableEffects.add(StormEffect.RANDOM_RADIUS);
        if (availableEffects.isEmpty()) return StormEffect.POLARITY_INVERT;
        return availableEffects.get(random.nextInt(availableEffects.size()));
    }

    private void applyStormEffect(MinecraftServer server) {
        PhantomMagnetManager magnetManager = PhantomMagnetManager.get(server);
        var magnets = magnetManager.getMagnets();
        for (var entry : magnets.entrySet()) {
            PhantomMagnetManager.PhantomMagnet magnet = entry.getValue();
            MagnetBackup backup = magnetBackups.get(entry.getKey());
            if (backup == null) continue;
            switch (currentEffect) {
                case POLARITY_INVERT -> magnet.setAttracting(!backup.attracting);
                case RANDOM_POLARITY -> magnet.setAttracting(random.nextBoolean());
                case POWER_OFF -> {
                    double powerReduction = 0.01 / stormIntensity;
                    magnet.setForceMultiplier(Math.max(0.001, powerReduction));
                }
                case POWER_BOOST -> {
                    double boostMultiplier = (1.5 + random.nextDouble() * 1.5) * stormIntensity;
                    double newBoostForce = backup.forceMultiplier * boostMultiplier;
                    magnet.setForceMultiplier(Math.min(newBoostForce, 10.0));
                }
                case POWER_REDUCE -> {
                    double reduceMultiplier = (0.1 + random.nextDouble() * 0.4) / stormIntensity;
                    magnet.setForceMultiplier(backup.forceMultiplier * reduceMultiplier);
                }
                case RANDOM_RADIUS -> {
                    double radiusMultiplier = 0.5 + (random.nextDouble() * stormIntensity);
                    double newRadius = backup.radius * radiusMultiplier;
                    magnet.setRadius(Math.min(Math.max(newRadius, 1.0), 500.0));
                }
            }
        }
        magnetManager.markDirty();
    }

    public boolean isStormActive() { return stormActive; }
    public StormEffect getCurrentEffect() { return currentEffect; }
    public long getTimeUntilNextStorm() {
        if (isCustomStorm) return -1;
        return Math.max(0, nextStormTime - System.currentTimeMillis());
    }
    public long getStormTimeRemaining() { return stormActive ? Math.max(0, stormEndTime - System.currentTimeMillis()) : 0; }
    public double getStormIntensity() { return stormIntensity; }

    @Override
    public net.minecraft.nbt.NbtCompound writeNbt(net.minecraft.nbt.NbtCompound nbt) {
        nbt.putLong("lastStormEndTime", lastStormEndTime);
        nbt.putLong("nextStormTime", nextStormTime);
        nbt.putBoolean("stormActive", stormActive);
        nbt.putLong("stormEndTime", stormEndTime);
        nbt.putBoolean("isCustomStorm", isCustomStorm);
        nbt.putDouble("stormIntensity", stormIntensity);
        if (currentEffect != null) nbt.putString("currentEffect", currentEffect.name());
        net.minecraft.nbt.NbtList backupsList = new net.minecraft.nbt.NbtList();
        for (var entry : magnetBackups.entrySet()) {
            net.minecraft.nbt.NbtCompound backupNbt = new net.minecraft.nbt.NbtCompound();
            backupNbt.putInt("magnetId", entry.getKey());
            backupNbt.put("backup", entry.getValue().toNbt());
            backupsList.add(backupNbt);
        }
        nbt.put("magnetBackups", backupsList);
        return nbt;
    }

    public static MagneticStormManager fromNbt(net.minecraft.nbt.NbtCompound nbt) {
        MagneticStormManager manager = new MagneticStormManager();
        manager.lastStormEndTime = nbt.getLong("lastStormEndTime");
        manager.nextStormTime = nbt.getLong("nextStormTime");
        manager.stormActive = nbt.getBoolean("stormActive");
        manager.stormEndTime = nbt.getLong("stormEndTime");
        manager.isCustomStorm = nbt.getBoolean("isCustomStorm");
        manager.stormIntensity = nbt.getDouble("stormIntensity");
        if (nbt.contains("currentEffect")) {
            try {
                manager.currentEffect = StormEffect.valueOf(nbt.getString("currentEffect"));
            } catch (IllegalArgumentException e) {
                manager.currentEffect = null;
            }
        }
        if (nbt.contains("magnetBackups")) {
            net.minecraft.nbt.NbtList backupsList = nbt.getList("magnetBackups", 10);
            for (int i = 0; i < backupsList.size(); i++) {
                net.minecraft.nbt.NbtCompound backupEntry = backupsList.getCompound(i);
                int magnetId = backupEntry.getInt("magnetId");
                MagnetBackup backup = MagnetBackup.fromNbt(backupEntry.getCompound("backup"));
                manager.magnetBackups.put(magnetId, backup);
            }
        }
        if (manager.stormActive && System.currentTimeMillis() >= manager.stormEndTime) {
            manager.stormActive = false;
            manager.isCustomStorm = false;
            manager.stormIntensity = 1.0;
            manager.magnetBackups.clear();
        }
        if (manager.nextStormTime == 0) manager.scheduleNextStorm();
        return manager;
    }

    private record MagnetBackup(double radius, double forceMultiplier, boolean attracting) {
        public net.minecraft.nbt.NbtCompound toNbt() {
            net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
            nbt.putDouble("radius", radius);
            nbt.putDouble("forceMultiplier", forceMultiplier);
            nbt.putBoolean("attracting", attracting);
            return nbt;
        }
        public static MagnetBackup fromNbt(net.minecraft.nbt.NbtCompound nbt) {
            return new MagnetBackup(nbt.getDouble("radius"), nbt.getDouble("forceMultiplier"), nbt.getBoolean("attracting"));
        }
    }

    public enum StormEffect {
        POLARITY_INVERT, RANDOM_POLARITY, POWER_OFF, POWER_BOOST, POWER_REDUCE, RANDOM_RADIUS
    }
}
