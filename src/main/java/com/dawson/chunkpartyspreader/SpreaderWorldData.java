package com.dawson.chunkpartyspreader;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles persistent storage for the player spiral index and home chunk assignments.
 * This data is attached to the Overworld's data storage.
 */
public class SpreaderWorldData extends SavedData {

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Constants and Static Utilities
     * ────────────────────────────────────────────────────────────────────────────*/

    private static final String DATA_NAME = "chunkpartyspreader";

    /**
     * Factory method to create a new instance from NBT.
     */
    public static SpreaderWorldData load(CompoundTag tag) {
        SpreaderWorldData data = new SpreaderWorldData();

        // --- 1. Load Spiral Index ---
        data.currentSpiralIndex = tag.getInt("SpiralIndex");

        // --- 2. Load Player Assignments ---
        ListTag list = tag.getList("Assignments", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID uuid = UUID.fromString(entry.getString("UUID"));
            int x = entry.getInt("X");
            int y = entry.getInt("Y");
            int z = entry.getInt("Z");
            data.playerAssignments.put(uuid, new BlockPos(x, y, z));
        }

        return data;
    }

    /**
     * Retrieves the singleton instance of the spreader data for the server.
     * Always retrieves from the Overworld regardless of the provided level's dimension.
     */
    public static SpreaderWorldData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                SpreaderWorldData::load,
                SpreaderWorldData::new,
                DATA_NAME
        );
    }

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Fields
     * ────────────────────────────────────────────────────────────────────────────*/

    private int currentSpiralIndex = 0;
    private final Map<UUID, BlockPos> playerAssignments = new HashMap<>();

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Constructors
     * ────────────────────────────────────────────────────────────────────────────*/

    public SpreaderWorldData() {}

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Public Methods
     * ────────────────────────────────────────────────────────────────────────────*/

    @Override
    public CompoundTag save(CompoundTag tag) {
        // --- 1. Save Spiral Index ---
        tag.putInt("SpiralIndex", currentSpiralIndex);

        // --- 2. Save Player Assignments ---
        ListTag list = new ListTag();
        for (Map.Entry<UUID, BlockPos> e : playerAssignments.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("UUID", e.getKey().toString());

            BlockPos pos = e.getValue();
            entry.putInt("X", pos.getX());
            entry.putInt("Y", pos.getY());
            entry.putInt("Z", pos.getZ());

            list.add(entry);
        }
        tag.put("Assignments", list);

        return tag;
    }

    /**
     * @return The current global counter for the spiral algorithm.
     */
    public int getCurrentSpiralIndex() {
        return currentSpiralIndex;
    }

    /**
     * Updates the spiral index and marks the data as dirty for saving.
     */
    public void setCurrentSpiralIndex(int idx) {
        this.currentSpiralIndex = idx;
        this.setDirty();
    }

    /**
     * Retrieves the stored home position for a player.
     * @return BlockPos or null if no assignment exists.
     */
    public BlockPos getAssignment(UUID uuid) {
        return playerAssignments.get(uuid);
    }

    /**
     * Maps a player UUID to a BlockPos and marks the data as dirty.
     */
    public void putAssignment(UUID uuid, BlockPos pos) {
        playerAssignments.put(uuid, pos);
        this.setDirty();
    }

    /**
     * Resets all data to default values. Used for debugging/testing.
     */
    public void reset() {
        this.currentSpiralIndex = 0;
        this.playerAssignments.clear();
        this.setDirty();
    }
}