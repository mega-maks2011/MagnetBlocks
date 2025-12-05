package net.m998.magnetblocks;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import java.util.*;

public class MagneticStormManager extends PersistentState {
    private static final String DATA_NAME = "magnetblocks_storms";

    // Конфиги
    public static boolean ENABLE_MAGNETIC_STORMS = true;
    public static final int MIN_STORM_INTERVAL = 180;
    public static final int MAX_STORM_INTERVAL = 360;
    public static final int MIN_STORM_DURATION = 4;
    public static final int MAX_STORM_DURATION = 14;
    public static final boolean STORM_POLARITY_INVERT = true;
    public static final boolean STORM_RANDOM_POLARITY = true;
    public static final boolean STORM_POWER_OFF = true;
    public static final boolean STORM_POWER_BOOST = true;
    public static final boolean STORM_POWER_REDUCE = true;
    public static final boolean STORM_RANDOM_RADIUS = true;

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
        if (!ENABLE_MAGNETIC_STORMS) return;
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
        int durationMinutes = MIN_STORM_DURATION + random.nextInt(MAX_STORM_DURATION - MIN_STORM_DURATION + 1);
        stormEndTime = System.currentTimeMillis() + (durationMinutes * 60L * 1000L);
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
        stormEndTime = System.currentTimeMillis() + (durationMinutes * 60L * 1000L);
        applyStormEffect(server);
        this.markDirty();
    }

    public void endStorm(MinecraftServer server) {
        if (!stormActive) return;
        restoreMagnetBackups(server);
        stormActive = false;
        stormIntensity = 1.0;
        magnetBackups.clear();
        if (!isCustomStorm) scheduleNextStorm();
        isCustomStorm = false;
        this.markDirty();
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
    }

    private void restoreMagnetBackups(MinecraftServer server) {
        PhantomMagnetManager magnetManager = PhantomMagnetManager.get(server);
        var magnets = magnetManager.getMagnets();
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
        magnetManager.markDirty();
    }

    private void scheduleNextStorm() {
        int intervalMinutes = MIN_STORM_INTERVAL + random.nextInt(MAX_STORM_INTERVAL - MIN_STORM_INTERVAL + 1);
        nextStormTime = System.currentTimeMillis() + (intervalMinutes * 60L * 1000L);
    }

    private StormEffect getRandomStormEffect() {
        List<StormEffect> availableEffects = new ArrayList<>();
        if (STORM_POLARITY_INVERT) availableEffects.add(StormEffect.POLARITY_INVERT);
        if (STORM_RANDOM_POLARITY) availableEffects.add(StormEffect.RANDOM_POLARITY);
        if (STORM_POWER_OFF) availableEffects.add(StormEffect.POWER_OFF);
        if (STORM_POWER_BOOST) availableEffects.add(StormEffect.POWER_BOOST);
        if (STORM_POWER_REDUCE) availableEffects.add(StormEffect.POWER_REDUCE);
        if (STORM_RANDOM_RADIUS) availableEffects.add(StormEffect.RANDOM_RADIUS);
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
        nbt.putLong("nextStormTime", nextStormTime);
        nbt.putBoolean("stormActive", stormActive);
        nbt.putLong("stormEndTime", stormEndTime);
        nbt.putBoolean("isCustomStorm", isCustomStorm);
        nbt.putDouble("stormIntensity", stormIntensity);

        // Сохраняем относительное время вместо абсолютного
        long currentTime = System.currentTimeMillis();
        nbt.putLong("savedAtTime", currentTime);
        nbt.putLong("relativeStormEndTime", stormActive ? stormEndTime - currentTime : 0);
        nbt.putLong("relativeNextStormTime", !stormActive ? nextStormTime - currentTime : 0);

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
        long currentTime = System.currentTimeMillis();
        long savedAtTime = nbt.contains("savedAtTime") ? nbt.getLong("savedAtTime") : currentTime;

        // Восстанавливаем относительное время
        if (nbt.contains("relativeStormEndTime") && nbt.contains("relativeNextStormTime")) {
            long timeDiff = currentTime - savedAtTime;

            manager.stormActive = nbt.getBoolean("stormActive");
            manager.isCustomStorm = nbt.getBoolean("isCustomStorm");
            manager.stormIntensity = nbt.getDouble("stormIntensity");

            if (manager.stormActive) {
                long remainingTime = nbt.getLong("relativeStormEndTime") - timeDiff;
                if (remainingTime > 0) {
                    manager.stormEndTime = currentTime + remainingTime;
                } else {
                    manager.stormActive = false;
                    manager.isCustomStorm = false;
                    manager.stormIntensity = 1.0;
                    manager.scheduleNextStorm();
                }
            } else {
                long untilNextStorm = nbt.getLong("relativeNextStormTime") - timeDiff;
                if (untilNextStorm > 0) {
                    manager.nextStormTime = currentTime + untilNextStorm;
                } else {
                    manager.scheduleNextStorm();
                }
            }
        } else {
            manager.nextStormTime = nbt.getLong("nextStormTime");
            manager.stormActive = nbt.getBoolean("stormActive");
            manager.stormEndTime = nbt.getLong("stormEndTime");
            manager.isCustomStorm = nbt.getBoolean("isCustomStorm");
            manager.stormIntensity = nbt.getDouble("stormIntensity");

            if (manager.stormActive && currentTime >= manager.stormEndTime) {
                manager.stormActive = false;
                manager.isCustomStorm = false;
                manager.stormIntensity = 1.0;
                manager.scheduleNextStorm();
            } else if (!manager.stormActive && currentTime >= manager.nextStormTime) {
                manager.scheduleNextStorm();
            }
        }

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

        if (manager.nextStormTime == 0) {
            manager.scheduleNextStorm();
        }

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
        POLARITY_INVERT("polarity_invert", ""),
        RANDOM_POLARITY("random_polarity", ""),
        POWER_OFF("power_off", ""),
        POWER_BOOST("power_boost", ""),
        POWER_REDUCE("power_reduce", ""),
        RANDOM_RADIUS("random_radius", "");

        private final String id;
        private final String displayName;

        StormEffect(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String getId() { return id; }
        public String getDisplayName() { return displayName; }

        public static StormEffect fromString(String name) {
            for (StormEffect effect : values()) {
                if (effect.name().equalsIgnoreCase(name) || effect.id.equalsIgnoreCase(name)) {
                    return effect;
                }
            }
            return null;
        }

        public static List<String> getEffectNames() {
            List<String> names = new ArrayList<>();
            for (StormEffect effect : values()) {
                names.add(effect.name().toLowerCase());
            }
            return names;
        }
    }
}
