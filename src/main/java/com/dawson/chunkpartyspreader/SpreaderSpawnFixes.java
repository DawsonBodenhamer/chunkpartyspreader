package com.dawson.chunkpartyspreader;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerSetSpawnEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ChunkPartySpreader.MODID)
public final class SpreaderSpawnFixes {

    private SpreaderSpawnFixes() {}

    // Guard to prevent infinite recursion when we re-fire the set spawn event
    private static final ThreadLocal<Boolean> IS_ADJUSTING_SPAWN = ThreadLocal.withInitial(() -> false);

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Event Handlers
     * ────────────────────────────────────────────────────────────────────────────*/

    // --- 1. Fix Bed Respawn (Keep spawn even if bed broken) ---
    @SubscribeEvent
    public static void onSetSpawn(PlayerSetSpawnEvent event) {
        // If we are currently adjusting, ignore to prevent loop
        if (IS_ADJUSTING_SPAWN.get()) return;

        // We only care about ensuring the spawn is FORCED (survives bed breaking)
        // If it's already forced, or if the new spawn is null (clearing spawn), we do nothing.
        if (event.isForced() || event.getNewSpawn() == null) return;

        // Check if the target is a Bed
        Level level = event.getEntity().level();
        if (level.isClientSide) return;

        BlockPos newPos = event.getNewSpawn();
        // Just blind-force it. If the player is setting a spawn, we want it to stick.
        // This covers Beds and Anchors.

        ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Intercepting Spawn Set at {}. Forcing persistence.", newPos);

        // We must cancel the event to stop the original "non-forced" set,
        // and apply our own "forced" set.
        event.setCanceled(true);

        IS_ADJUSTING_SPAWN.set(true);
        try {
            if (event.getEntity() instanceof ServerPlayer sp) {
                // Call the method again, but with forced = true
                sp.setRespawnPosition(event.getSpawnLevel(), newPos, 0.0f, true, true);
            }
        } finally {
            IS_ADJUSTING_SPAWN.set(false);
        }
    }

    // --- 2. Void Safety Platform Checks ---

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ensureNotVoid(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ensureNotVoid(player);
        }
    }

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Private Helpers
     * ────────────────────────────────────────────────────────────────────────────*/

    private static void ensureNotVoid(ServerPlayer player) {
        if (!ModList.get().isLoaded("chunkbychunk")) return;

        // Check for Stasis Tag
        // If the player is currently waiting for the chunk to generate via SpreaderEvents,
        // we must NOT interfere. SpreaderEvents has them floating safely at Y=320 with NoGravity.
        if (player.getTags().contains("cps_waiting_for_chunk")) {
            ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Skipping void check for {} (In Stasis).", player.getName().getString());
            return;
        }

        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();

        level.getChunk(pos);
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        int minY = level.getMinBuildHeight();

        // If ground is effectively at the bottom of the world
        if (groundY > minY + 1) {
            return;
        }

        ChunkPartySpreader.LOGGER.warn("[Chunk Party Spreader] - Void detected under {}. Emergency platform activated at {}", player.getName().getString(), pos);

        // --- Platform Construction ---
        int floorY = Math.max(level.getSeaLevel() - 1, minY + 1);
        BlockPos floorCenter = new BlockPos(pos.getX(), floorY, pos.getZ());

        if (level.getBlockState(floorCenter).isAir()) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlockAndUpdate(floorCenter.offset(dx, 0, dz), Blocks.STONE.defaultBlockState());
                }
            }
        }

        // --- Emergency Teleport ---
        player.teleportTo(level,
                floorCenter.getX() + 0.5,
                floorCenter.getY() + 1.0,
                floorCenter.getZ() + 0.5,
                player.getYRot(),
                player.getXRot()
        );
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0f;
    }
}