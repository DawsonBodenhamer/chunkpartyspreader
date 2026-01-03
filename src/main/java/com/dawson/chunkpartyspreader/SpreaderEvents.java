package com.dawson.chunkpartyspreader;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = ChunkPartySpreader.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SpreaderEvents {

    // --- State Management for Stasis ---
    // Tracks players floating in the sky waiting for their chunk to generate.
    private static final Map<UUID, PendingTeleport> PENDING_TARGETS = new HashMap<>();
    private static final String TAG_WAITING = "cps_waiting_for_chunk";
    private static final int TIMEOUT_TICKS = 600; // 30 seconds max wait

    // StabilityCounter to track how many checks the chunk has passed
    private static class PendingTeleport {
        final ChunkPos targetChunk;
        final long startTick;
        int stabilityCounter = 0;

        PendingTeleport(ChunkPos targetChunk, long startTick) {
            this.targetChunk = targetChunk;
            this.startTick = startTick;
        }
    }

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Event Handlers
     * ────────────────────────────────────────────────────────────────────────────*/

    // --- 1. Server Starting: Disable CBC Auto-Gen & Align Spawn ---
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld(); // Safe to access here

        // A. Disable ChunkByChunk's startup generation
        // This ensures the world stays empty until we explicitly request a chunk.
        if (ModList.get().isLoaded("chunkbychunk")) {
            ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - ServerStarting: Disabling CBC initialChunks...");
            ChunkByChunkCompat.disableCbcStartupInitialChunks(server);
        }

        // B. Align World Spawn to Spiral Index 0
        int offX = CPSConfig.CENTER_OFFSET_X.get();
        int offZ = CPSConfig.CENTER_OFFSET_Z.get();

        int blockX = (offX * 16) + 8;
        int blockZ = (offZ * 16) + 8;
        BlockPos targetSpawn = new BlockPos(blockX, 64, blockZ);

        ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Aligning Default World Spawn to Spiral Center: {}", targetSpawn);
        level.setDefaultSpawnPos(targetSpawn, 0.0f);
    }

    // --- 2. First-Join Logic  ---
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !player.level().isClientSide) {
            processPlayerJoin(player);
        }
    }

    // --- 3. Stasis Polling (Server Tick) ---
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING_TARGETS.isEmpty()) return;

        MinecraftServer server = event.getServer();
        if (server == null) return;

        Iterator<Map.Entry<UUID, PendingTeleport>> it = PENDING_TARGETS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingTeleport> entry = it.next();
            UUID uuid = entry.getKey();
            PendingTeleport pending = entry.getValue();

            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                it.remove();
                continue;
            }

            // Poll every 20 ticks
            if (player.getServer().getTickCount() % 20 != 0) continue;

            ServerLevel level = player.serverLevel();
            int centerBlockX = pending.targetChunk.getMinBlockX() + 8;
            int centerBlockZ = pending.targetChunk.getMinBlockZ() + 8;

            int groundY = getTrueSurfaceY(level, centerBlockX, centerBlockZ);
            int minBuild = level.getMinBuildHeight();

            boolean isGroundDetected = groundY > minBuild + 1;
            boolean isTimeout = (player.getServer().getTickCount() - pending.startTick) > TIMEOUT_TICKS;

            if (isGroundDetected) {
                pending.stabilityCounter++;
                ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Ground detected at Y={}. Stability {}/3", groundY, pending.stabilityCounter);
            } else {
                pending.stabilityCounter = 0;
            }

            if (pending.stabilityCounter >= 3) {
                ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Chunk stable! Releasing {}.", player.getName().getString());

                BlockPos finalHome = new BlockPos(centerBlockX, groundY + 1, centerBlockZ);
                SpreaderWorldData.get(level).putAssignment(uuid, finalHome);

                level.getChunkSource().removeRegionTicket(TicketType.PLAYER, pending.targetChunk, 3, pending.targetChunk);
                player.removeTag(TAG_WAITING);
                player.setNoGravity(false);
                player.teleportTo(level, finalHome.getX() + 0.5, finalHome.getY(), finalHome.getZ() + 0.5, player.getYRot(), player.getXRot());
                player.setRespawnPosition(level.dimension(), finalHome, player.getYRot(), true, false);

                it.remove();
            } else if (isTimeout) {
                ChunkPartySpreader.LOGGER.warn("[Chunk Party Spreader] - Generation timeout (60s) for {}. Releasing to gravity (fallback).", player.getName().getString());
                level.getChunkSource().removeRegionTicket(TicketType.PLAYER, pending.targetChunk, 3, pending.targetChunk);
                player.removeTag(TAG_WAITING);
                player.setNoGravity(false);
                it.remove();
            }
        }
    }

    // --- 4. Respawn Fallback Logic ---
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide || event.isEndConquered()) {
            return;
        }

        ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Player respawning: {}. Checking for spawn point override...", player.getName().getString());

        MinecraftServer server = player.getServer();
        if (server == null) return;

        if (hasValidSpawnBlockOrForced(player, server)) {
            ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Player has valid/forced spawn. No intervention.");
            return;
        }

        ServerLevel overworld = server.overworld();
        SpreaderWorldData data = SpreaderWorldData.get(overworld);
        BlockPos home = data.getAssignment(player.getUUID());

        if (home != null) {
            ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - No valid bed found. Teleporting to Spiral Home: {}", home);
            player.teleportTo(overworld, home.getX() + 0.5, home.getY(), home.getZ() + 0.5, player.getYRot(), player.getXRot());
            player.setRespawnPosition(overworld.dimension(), home, player.getYRot(), true, false);
        } else {
            ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - No Spiral Assignment found for respawning player.");
        }
    }

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Public API / Internal Logic (Exposed for Testing)
     * ────────────────────────────────────────────────────────────────────────────*/

    /**
     * Executes the core logic for assigning a player to a chunk.
     * Separated from the event for synthetic testing.
     */
    public static void processPlayerJoin(ServerPlayer player) {
        ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Processing join for: {}", player.getName().getString());

        MinecraftServer server = player.getServer();
        if (server == null) return;

        ServerLevel level = server.overworld();
        UUID uuid = player.getUUID();
        SpreaderWorldData data = SpreaderWorldData.get(level);

        // A. Existing Assignment Check
        BlockPos existingAssignment = data.getAssignment(uuid);
        if (existingAssignment != null) {
            ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Player already has assignment at: {}", existingAssignment);

            // Note: For FakePlayers in simulation, tags might not persist across 'joins' if object is recreated.
            if (player.getTags().contains(TAG_WAITING)) {
                ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Player has waiting tag. Resuming stasis polling...");
                ChunkPos cPos = new ChunkPos(existingAssignment);
                PENDING_TARGETS.put(uuid, new PendingTeleport(cPos, server.getTickCount()));

                player.setNoGravity(true);
                player.teleportTo(level, existingAssignment.getX() + 0.5, 320, existingAssignment.getZ() + 0.5, player.getYRot(), player.getXRot());
                level.getChunkSource().addRegionTicket(TicketType.PLAYER, cPos, 3, cPos);
            }
            return;
        }

        ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - No assignment found. Beginning spiral calculation...");

        // B. Spiral Calculation
        int spacing = CPSConfig.GRID_SPACING_CHUNKS.get();
        int offX = CPSConfig.CENTER_OFFSET_X.get();
        int offZ = CPSConfig.CENTER_OFFSET_Z.get();
        boolean skipOceans = CPSConfig.SKIP_OCEANS.get();

        int idx = data.getCurrentSpiralIndex();
        ChunkPos chosenChunk = null;

        for (int attempts = 0; attempts < 10000; attempts++) {
            ChunkPos candidate = SpiralCalculator.chunkForIndex(idx, spacing, offX, offZ);

            int bx = candidate.getMinBlockX() + 8;
            int bz = candidate.getMinBlockZ() + 8;
            BlockPos biomePos = new BlockPos(bx, level.getSeaLevel(), bz);

            // Checks for Ocean OR River
            boolean isOcean = level.getBiome(biomePos).is(BiomeTags.IS_OCEAN);
            boolean isRiver = level.getBiome(biomePos).is(BiomeTags.IS_RIVER);

            if (skipOceans && (isOcean || isRiver)) {
                ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Skipping Index {} at {} (Biome: {}).",
                        idx, candidate, isOcean ? "Ocean" : "River");
                idx++;
                data.setCurrentSpiralIndex(idx);
                continue;
            }

            chosenChunk = candidate;
            ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Found valid chunk at index {}: {}", idx, chosenChunk);
            break;
        }

        if (chosenChunk == null) {
            ChunkPartySpreader.LOGGER.error("[Chunk Party Spreader] - Failed to find valid chunk after 10000 attempts. Using fallback.");
            chosenChunk = SpiralCalculator.chunkForIndex(idx, spacing, offX, offZ);
        }

        // C. Reserve Index & Save Assignment
        data.setCurrentSpiralIndex(idx + 1);

        int blockX = chosenChunk.getMinBlockX() + 8;
        int blockZ = chosenChunk.getMinBlockZ() + 8;
        BlockPos tempPos = new BlockPos(blockX, 320, blockZ);
        data.putAssignment(uuid, tempPos);

        // D. Force Chunk Loading
        ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Adding PLAYER ticket to force load chunk {}", chosenChunk);
        level.getChunkSource().addRegionTicket(TicketType.PLAYER, chosenChunk, 3, chosenChunk);

        // E. Trigger Generation (Direct API Call)
        if (ModList.get().isLoaded("chunkbychunk")) {
            ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Requesting generation via ChunkByChunk API for {}", tempPos);
            try {
                boolean success = ChunkByChunkCompat.generateChunk(level, tempPos);
                ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - API Request Accepted: {}", success);
            } catch (Exception e) {
                ChunkPartySpreader.LOGGER.error("[Chunk Party Spreader] - API execution failed", e);
            }
        }

        // F. Enable Stasis
        ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Putting player in stasis at Y=320 while chunk generates...");
        player.addTag(TAG_WAITING);
        player.setNoGravity(true);
        player.teleportTo(level, tempPos.getX() + 0.5, 320, tempPos.getZ() + 0.5, player.getYRot(), player.getXRot());

        PENDING_TARGETS.put(uuid, new PendingTeleport(chosenChunk, server.getTickCount()));
    }

    /**
     * Simulation Helper: Check if a UUID is currently being tracked in stasis.
     */
    public static boolean isPending(UUID uuid) {
        return PENDING_TARGETS.containsKey(uuid);
    }

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Private Helpers
     * ────────────────────────────────────────────────────────────────────────────*/

    /**
     * Scans from the top of the world down to find the first non-air, non-leaf block.
     * This bypasses potentially stale Heightmaps in newly generated chunks.
     */
    private static int getTrueSurfaceY(ServerLevel level, int x, int z) {
        int maxY = level.getMaxBuildHeight() - 1;
        int minY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, maxY, z);

        for (int y = maxY; y > minY; y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.is(BlockTags.LEAVES)) {
                return y;
            }
        }
        return minY;
    }

    private static boolean hasValidSpawnBlockOrForced(ServerPlayer player, MinecraftServer server) {
        if (player.isRespawnForced()) return true;

        BlockPos respawnPos = player.getRespawnPosition();
        if (respawnPos == null) return false;

        ServerLevel respawnLevel = server.getLevel(player.getRespawnDimension());
        if (respawnLevel == null) return false;

        BlockState state = respawnLevel.getBlockState(respawnPos);

        if (state.is(BlockTags.BEDS)) return true;

        if (state.is(Blocks.RESPAWN_ANCHOR)) {
            Integer charge = state.getValue(RespawnAnchorBlock.CHARGE);
            return charge > 0;
        }

        return false;
    }
}