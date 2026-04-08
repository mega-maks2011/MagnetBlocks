package net.m998.magnetblocks;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class MagneticStormManager extends PersistentState {
    private static final String DATA_NAME = "magnetblocks_storms";
    public static boolean ENABLE_MAGNETIC_STORMS = true;
    private static final int TICKS_PER_MINUTE = 20 * 60;
    private static final int MIN_STORM_INTERVAL_MINUTES = 180;
    private static final int MAX_STORM_INTERVAL_MINUTES = 360;
    private static final int MIN_STORM_DURATION_MINUTES = 4;
    private static final int MAX_STORM_DURATION_MINUTES = 14;

    private long nextStormTick = 0;
    private boolean stormActive = false;
    private long stormEndTick = 0;
    private StormEffect currentEffect = null;
    private double stormIntensity = 1.0;
    private boolean isCustomStorm = false;
    private final Map<Integer, MagnetBackup> magnetBackups = new HashMap<>();
    private final Random random = new Random();

    public MagneticStormManager() { super(); }

    public static MagneticStormManager get(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        return world.getPersistentStateManager().getOrCreate(MagneticStormManager::fromNbt, MagneticStormManager::new, DATA_NAME);
    }

    public void tick(MinecraftServer server) {
        if (!ENABLE_MAGNETIC_STORMS) return;
        long currentTick = server.getOverworld().getTime();
        if (stormActive) {
            if (currentTick >= stormEndTick) endStorm(server);
        } else {
            if (!isCustomStorm && currentTick >= nextStormTick) startStorm(server);
        }
    }

    public void startStorm(MinecraftServer server) {
        if (stormActive) return;
        saveMagnetBackups(server);
        stormActive = true;
        isCustomStorm = false;
        stormIntensity = 1.0;
        currentEffect = getRandomStormEffect();
        int durationMinutes = MIN_STORM_DURATION_MINUTES + random.nextInt(MAX_STORM_DURATION_MINUTES - MIN_STORM_DURATION_MINUTES + 1);
        stormEndTick = server.getOverworld().getTime() + durationMinutes * TICKS_PER_MINUTE;
        applyStormEffect(server);
        this.markDirty();
    }

    public void startCustomStorm(MinecraftServer server, int durationMinutes, double intensity, StormEffect effect) {
        if (stormActive) return;
        saveMagnetBackups(server);
        stormActive = true;
        isCustomStorm = true;
        stormIntensity = Math.min(intensity, 5.0);
        currentEffect = effect != null ? effect : getRandomStormEffect();
        stormEndTick = server.getOverworld().getTime() + durationMinutes * TICKS_PER_MINUTE;
        applyStormEffect(server);
        this.markDirty();
    }

    public void endStorm(MinecraftServer server) {
        if (!stormActive) return;
        restoreMagnetBackups(server);
        stormActive = false;
        stormIntensity = 1.0;
        magnetBackups.clear();
        if (!isCustomStorm) scheduleNextStorm(server);
        isCustomStorm = false;
        this.markDirty();
    }

    private void saveMagnetBackups(MinecraftServer server) {
        magnetBackups.clear();
        for (ServerWorld world : server.getWorlds()) {
            PhantomMagnetManager manager = PhantomMagnetManager.get(world);
            var magnets = manager.getMagnets();
            for (var entry : magnets.entrySet()) {
                int magnetId = entry.getKey();
                PhantomMagnetManager.PhantomMagnet magnet = entry.getValue();
                magnetBackups.put(magnetId, new MagnetBackup(magnet.getRadius(), magnet.getForceMultiplier(), magnet.isAttracting()));
            }
        }
    }

    private void restoreMagnetBackups(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            PhantomMagnetManager manager = PhantomMagnetManager.get(world);
            var magnets = manager.getMagnets();
            for (var entry : magnets.entrySet()) {
                int magnetId = entry.getKey();
                PhantomMagnetManager.PhantomMagnet magnet = entry.getValue();
                MagnetBackup backup = magnetBackups.get(magnetId);
                if (backup != null) {
                    magnet.setRadius(backup.radius);
                    magnet.setForceMultiplier(backup.forceMultiplier);
                    magnet.setAttracting(backup.attracting);
                }
            }
            manager.markDirty();
        }
    }

    private void scheduleNextStorm(MinecraftServer server) {
        int intervalMinutes = MIN_STORM_INTERVAL_MINUTES + random.nextInt(MAX_STORM_INTERVAL_MINUTES - MIN_STORM_INTERVAL_MINUTES + 1);
        nextStormTick = server.getOverworld().getTime() + intervalMinutes * TICKS_PER_MINUTE;
    }

    private StormEffect getRandomStormEffect() {
        StormEffect[] effects = StormEffect.values();
        return effects[random.nextInt(effects.length)];
    }

    private void applyStormEffect(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            PhantomMagnetManager manager = PhantomMagnetManager.get(world);
            var magnets = manager.getMagnets();
            for (var entry : magnets.entrySet()) {
                PhantomMagnetManager.PhantomMagnet magnet = entry.getValue();
                MagnetBackup backup = magnetBackups.get(entry.getKey());
                if (backup == null) continue;
                switch (currentEffect) {
                    case POLARITY_INVERT -> magnet.setAttracting(!backup.attracting);
                    case RANDOM_POLARITY -> magnet.setAttracting(random.nextBoolean());
                    case POWER_OFF -> magnet.setForceMultiplier(Math.max(0.001, 0.01 / stormIntensity));
                    case POWER_BOOST -> {
                        double boost = (1.5 + random.nextDouble() * 1.5) * stormIntensity;
                        magnet.setForceMultiplier(Math.min(backup.forceMultiplier * boost, 10.0));
                    }
                    case POWER_REDUCE -> {
                        double reduce = (0.1 + random.nextDouble() * 0.4) / stormIntensity;
                        magnet.setForceMultiplier(backup.forceMultiplier * reduce);
                    }
                    case RANDOM_RADIUS -> {
                        double mult = 0.5 + random.nextDouble() * stormIntensity;
                        magnet.setRadius(Math.min(Math.max(backup.radius * mult, 1.0), 500.0));
                    }
                }
            }
            manager.markDirty();
        }
    }

    public boolean isStormActive() { return stormActive; }
    public StormEffect getCurrentEffect() { return currentEffect; }
    public long getTimeUntilNextStorm(MinecraftServer server) {
        return isCustomStorm ? -1 : Math.max(0, nextStormTick - server.getOverworld().getTime());
    }
    public long getStormTimeRemaining(MinecraftServer server) {
        return stormActive ? Math.max(0, stormEndTick - server.getOverworld().getTime()) : 0;
    }
    public double getStormIntensity() { return stormIntensity; }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putLong("nextStormTick", nextStormTick);
        nbt.putBoolean("stormActive", stormActive);
        nbt.putLong("stormEndTick", stormEndTick);
        nbt.putBoolean("isCustomStorm", isCustomStorm);
        nbt.putDouble("stormIntensity", stormIntensity);
        if (currentEffect != null) nbt.putString("currentEffect", currentEffect.name());
        NbtList backupsList = new NbtList();
        for (var entry : magnetBackups.entrySet()) {
            NbtCompound backupNbt = new NbtCompound();
            backupNbt.putInt("magnetId", entry.getKey());
            backupNbt.put("backup", entry.getValue().toNbt());
            backupsList.add(backupNbt);
        }
        nbt.put("magnetBackups", backupsList);
        return nbt;
    }

    public static MagneticStormManager fromNbt(NbtCompound nbt) {
        MagneticStormManager manager = new MagneticStormManager();
        manager.nextStormTick = nbt.getLong("nextStormTick");
        manager.stormActive = nbt.getBoolean("stormActive");
        manager.stormEndTick = nbt.getLong("stormEndTick");
        manager.isCustomStorm = nbt.getBoolean("isCustomStorm");
        manager.stormIntensity = nbt.getDouble("stormIntensity");
        if (nbt.contains("currentEffect")) {
            try { manager.currentEffect = StormEffect.valueOf(nbt.getString("currentEffect")); }
            catch (IllegalArgumentException ignored) {}
        }
        NbtList backupsList = nbt.getList("magnetBackups", 10);
        for (int i = 0; i < backupsList.size(); i++) {
            NbtCompound entry = backupsList.getCompound(i);
            int id = entry.getInt("magnetId");
            MagnetBackup backup = MagnetBackup.fromNbt(entry.getCompound("backup"));
            manager.magnetBackups.put(id, backup);
        }
        return manager;
    }

    private record MagnetBackup(double radius, double forceMultiplier, boolean attracting) {
        public NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putDouble("radius", radius);
            nbt.putDouble("forceMultiplier", forceMultiplier);
            nbt.putBoolean("attracting", attracting);
            return nbt;
        }
        public static MagnetBackup fromNbt(NbtCompound nbt) {
            return new MagnetBackup(nbt.getDouble("radius"), nbt.getDouble("forceMultiplier"), nbt.getBoolean("attracting"));
        }
    }

    public enum StormEffect {
        POLARITY_INVERT, RANDOM_POLARITY, POWER_OFF, POWER_BOOST, POWER_REDUCE, RANDOM_RADIUS
    }
}
