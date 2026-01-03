package com.dawson.chunkpartyspreader;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.fml.ModList;
import xyz.immortius.chunkbychunk.server.world.ChunkSpawnController;
import xyz.immortius.chunkbychunk.server.world.SkyChunkGenerator;

import java.lang.reflect.Field;

/**
 * Direct integration with Chunk By Chunk API.
 * This class MUST only be accessed if the mod is loaded.
 */
public final class ChunkByChunkCompat {

    private ChunkByChunkCompat() {}

    // --- Reflection Cache ---
    private static Field SEAL_COVER_BLOCK_FIELD;

    /**
     * Triggers the generation of a specific chunk using Chunk By Chunk's internal controller.
     */
    public static boolean generateChunk(ServerLevel level, BlockPos centerPos) {
        MinecraftServer server = level.getServer();
        ChunkSpawnController controller = ChunkSpawnController.get(server);
        return controller.request(level, "", false, centerPos);
    }

    /**
     * Forces Chunk By Chunk's SkyChunkGenerator to spawn 0 initial chunks on startup.
     * This prevents a "Ghost Chunk" from generating at the default world spawn.
     */
    public static void disableCbcStartupInitialChunks(MinecraftServer server) {
        if (!ModList.get().isLoaded("chunkbychunk")) {
            return;
        }

        ServerLevel overworld = server.overworld();
        ChunkGenerator cg = overworld.getChunkSource().getGenerator();

        // 1. Verify we are actually running on a CBC world
        if (!(cg instanceof SkyChunkGenerator skyGen)) {
            ChunkPartySpreader.LOGGER.info(
                    "[Chunk Party Spreader] - CBC detected but overworld generator is not SkyChunkGenerator: {}",
                    cg.getClass().getName()
            );
            return;
        }

        int before = skyGen.getInitialChunks();
        if (before == 0) {
            ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - CBC SkyChunkGenerator initialChunks already 0; nothing to change.");
            return;
        }

        // 2. Extract existing settings so we don't break the user's config
        ResourceKey<Level> generationLevel = skyGen.getGenerationLevel();
        SkyChunkGenerator.EmptyGenerationType generationType = skyGen.getGenerationType();
        Block sealBlock = skyGen.getSealBlock();
        Block sealCoverBlock = getSealCoverBlockReflective(skyGen);
        boolean chunkSpawnerAllowed = skyGen.isChunkSpawnerAllowed();
        boolean randomChunkSpawnerAllowed = skyGen.isRandomChunkSpawnerAllowed();

        // 3. Re-apply configuration but force initialChunks = 0
        skyGen.configure(
                generationLevel,
                generationType,
                sealBlock,
                sealCoverBlock,
                0, // <--- The Fix: Force Zero Initial Chunks
                chunkSpawnerAllowed,
                randomChunkSpawnerAllowed
        );

        ChunkPartySpreader.LOGGER.warn(
                "[Chunk Party Spreader] - CBC startup initial chunk spawning DISABLED (initialChunks {} -> 0).",
                before
        );
    }

    /**
     * Helper to read the private 'sealCoverBlock' field via reflection.
     */
    private static Block getSealCoverBlockReflective(SkyChunkGenerator gen) {
        try {
            if (SEAL_COVER_BLOCK_FIELD == null) {
                Field f = SkyChunkGenerator.class.getDeclaredField("sealCoverBlock");
                f.setAccessible(true);
                SEAL_COVER_BLOCK_FIELD = f;
            }
            return (Block) SEAL_COVER_BLOCK_FIELD.get(gen);
        } catch (Throwable t) {
            ChunkPartySpreader.LOGGER.error(
                    "[Chunk Party Spreader] - Failed to read CBC SkyChunkGenerator.sealCoverBlock via reflection; falling back to sealBlock.",
                    t
            );
            return gen.getSealBlock();
        }
    }
}