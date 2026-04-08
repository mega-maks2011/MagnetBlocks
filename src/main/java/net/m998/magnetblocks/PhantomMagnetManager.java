package net.m998.magnetblocks;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PhantomMagnetManager extends PersistentState {
    private static final String DATA_NAME = "magnetblocks_phantom_magnets";
    private static final double MAX_FORCE_MULTIPLIER = 10.0;
    private final Map<Integer, PhantomMagnet> magnets = new HashMap<>();
    private final Map<UUID, WhitelistEntry> whitelist = new HashMap<>();
    private int nextId = 1;
    private boolean clearConfirmation = false;

    public PhantomMagnetManager() {
        super();
    }

    public static PhantomMagnetManager get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(PhantomMagnetManager::fromNbt, PhantomMagnetManager::new, DATA_NAME);
    }

    public int createMagnet(BlockPos pos, double radius, double forceMultiplier, boolean attracting) {
        clearConfirmation = false;
        int id = findNextAvailableId();
        double clampedForce = Math.min(forceMultiplier, MAX_FORCE_MULTIPLIER);
        magnets.put(id, new PhantomMagnet(pos, radius, clampedForce, attracting));
        if (id >= nextId) nextId = id + 1;
        this.markDirty();
        return id;
    }

    private int findNextAvailableId() {
        if (magnets.isEmpty()) return 1;
        for (int i = 1; i <= nextId + 1; i++) {
            if (!magnets.containsKey(i)) return i;
        }
        return nextId;
    }

    public boolean removeMagnet(int id) {
        clearConfirmation = false;
        boolean removed = magnets.remove(id) != null;
        if (removed) {
            if (id == nextId - 1) {
                nextId = id;
                while (nextId > 1 && !magnets.containsKey(nextId - 1)) nextId--;
            }
            this.markDirty();
        }
        return removed;
    }

    public Map<Integer, PhantomMagnet> getMagnets() {
        return magnets;
    }

    public void clearAllMagnets(boolean confirm) {
        if (confirm && clearConfirmation) {
            magnets.clear();
            nextId = 1;
            clearConfirmation = false;
            this.markDirty();
        } else {
            clearConfirmation = true;
        }
    }

    public boolean isClearConfirmationPending() {
        return clearConfirmation;
    }

    public int getMagnetCount() {
        return magnets.size();
    }

    public static double getMaxForceMultiplier() {
        return MAX_FORCE_MULTIPLIER;
    }

    public void addPlayerToWhitelist(UUID playerUUID, double strength) {
        whitelist.put(playerUUID, new WhitelistEntry(playerUUID, strength));
        this.markDirty();
    }

    public boolean removePlayerFromWhitelist(UUID playerUUID) {
        boolean removed = whitelist.remove(playerUUID) != null;
        if (removed) this.markDirty();
        return removed;
    }

    public Double getPlayerStrength(UUID playerUUID) {
        WhitelistEntry entry = whitelist.get(playerUUID);
        return entry != null ? entry.strength : null;
    }

    public Map<UUID, WhitelistEntry> getWhitelist() {
        return whitelist;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putInt("nextId", nextId);
        nbt.putBoolean("clearConfirmation", clearConfirmation);
        NbtList magnetsList = new NbtList();
        for (Map.Entry<Integer, PhantomMagnet> entry : magnets.entrySet()) {
            NbtCompound magnetNbt = new NbtCompound();
            magnetNbt.putInt("id", entry.getKey());
            magnetNbt.put("magnet", entry.getValue().toNbt());
            magnetsList.add(magnetNbt);
        }
        nbt.put("magnets", magnetsList);
        NbtList whitelistList = new NbtList();
        for (WhitelistEntry entry : whitelist.values()) {
            whitelistList.add(entry.toNbt());
        }
        nbt.put("whitelist", whitelistList);
        return nbt;
    }

    public static PhantomMagnetManager fromNbt(NbtCompound nbt) {
        PhantomMagnetManager manager = new PhantomMagnetManager();
        manager.nextId = nbt.getInt("nextId");
        manager.clearConfirmation = nbt.getBoolean("clearConfirmation");
        NbtList magnetsList = nbt.getList("magnets", 10);
        for (int i = 0; i < magnetsList.size(); i++) {
            NbtCompound magnetEntry = magnetsList.getCompound(i);
            int id = magnetEntry.getInt("id");
            PhantomMagnet magnet = PhantomMagnet.fromNbt(magnetEntry.getCompound("magnet"));
            manager.magnets.put(id, magnet);
        }
        NbtList whitelistList = nbt.getList("whitelist", 10);
        for (int i = 0; i < whitelistList.size(); i++) {
            WhitelistEntry entry = WhitelistEntry.fromNbt(whitelistList.getCompound(i));
            manager.whitelist.put(entry.playerUUID, entry);
        }
        return manager;
    }

    public static class PhantomMagnet {
        private final BlockPos pos;
        private double radius;
        private double forceMultiplier;
        private boolean attracting;

        public PhantomMagnet(BlockPos pos, double radius, double forceMultiplier, boolean attracting) {
            this.pos = pos;
            this.radius = radius;
            this.forceMultiplier = Math.min(forceMultiplier, MAX_FORCE_MULTIPLIER);
            this.attracting = attracting;
        }

        public BlockPos getPos() { return pos; }
        public double getRadius() { return radius; }
        public double getForceMultiplier() { return forceMultiplier; }
        public boolean isAttracting() { return attracting; }

        public void setRadius(double radius) { this.radius = radius; }
        public void setForceMultiplier(double forceMultiplier) { this.forceMultiplier = Math.min(forceMultiplier, MAX_FORCE_MULTIPLIER); }
        public void setAttracting(boolean attracting) { this.attracting = attracting; }

        public NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putInt("x", pos.getX());
            nbt.putInt("y", pos.getY());
            nbt.putInt("z", pos.getZ());
            nbt.putDouble("radius", radius);
            nbt.putDouble("forceMultiplier", forceMultiplier);
            nbt.putBoolean("attracting", attracting);
            return nbt;
        }

        public static PhantomMagnet fromNbt(NbtCompound nbt) {
            BlockPos pos = new BlockPos(nbt.getInt("x"), nbt.getInt("y"), nbt.getInt("z"));
            double radius = nbt.getDouble("radius");
            double forceMultiplier = Math.min(nbt.getDouble("forceMultiplier"), MAX_FORCE_MULTIPLIER);
            boolean attracting = nbt.getBoolean("attracting");
            return new PhantomMagnet(pos, radius, forceMultiplier, attracting);
        }
    }

    public static class WhitelistEntry {
        private final UUID playerUUID;
        private final double strength;

        public WhitelistEntry(UUID playerUUID, double strength) {
            this.playerUUID = playerUUID;
            this.strength = Math.max(0.001, Math.min(strength, 10.0));
        }

        public double getStrength() { return strength; }

        public NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putUuid("playerUUID", playerUUID);
            nbt.putDouble("strength", strength);
            return nbt;
        }

        public static WhitelistEntry fromNbt(NbtCompound nbt) {
            return new WhitelistEntry(nbt.getUuid("playerUUID"), nbt.getDouble("strength"));
        }
    }
}
