package net.m998.magnetblocks;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MagnetWhitelistManager extends PersistentState {
    private static final String DATA_NAME = "magnetblocks_whitelist";
    private final Map<UUID, WhitelistEntry> whitelist = new HashMap<>();

    public MagnetWhitelistManager() { super(); }

    public static MagnetWhitelistManager get(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        return world.getPersistentStateManager().getOrCreate(MagnetWhitelistManager::fromNbt, MagnetWhitelistManager::new, DATA_NAME);
    }

    public void addPlayer(UUID playerUUID, double strength) {
        whitelist.put(playerUUID, new WhitelistEntry(playerUUID, strength));
        this.markDirty();
    }

    public boolean removePlayer(UUID playerUUID) {
        boolean removed = whitelist.remove(playerUUID) != null;
        if (removed) this.markDirty();
        return removed;
    }

    public Double getPlayerStrength(UUID playerUUID) {
        WhitelistEntry entry = whitelist.get(playerUUID);
        return entry != null ? entry.strength : null;
    }

    public Map<UUID, WhitelistEntry> getWhitelist() { return whitelist; }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList whitelistList = new NbtList();
        for (WhitelistEntry entry : whitelist.values()) whitelistList.add(entry.toNbt());
        nbt.put("whitelist", whitelistList);
        return nbt;
    }

    public static MagnetWhitelistManager fromNbt(NbtCompound nbt) {
        MagnetWhitelistManager manager = new MagnetWhitelistManager();
        if (nbt.contains("whitelist")) {
            NbtList whitelistList = nbt.getList("whitelist", 10);
            for (int i = 0; i < whitelistList.size(); i++) {
                WhitelistEntry entry = WhitelistEntry.fromNbt(whitelistList.getCompound(i));
                manager.whitelist.put(entry.playerUUID, entry);
            }
        }
        return manager;
    }

    public static class WhitelistEntry {
        private final UUID playerUUID;
        private final double strength;

        public WhitelistEntry(UUID playerUUID, double strength) {
            this.playerUUID = playerUUID;
            this.strength = Math.max(0.1, Math.min(strength, 10.0));
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
