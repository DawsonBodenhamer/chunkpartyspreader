package com.dawson.chunkpartyspreader;

import net.minecraft.world.level.ChunkPos;

/**
 * Utility for calculating geographic offsets based on a square spiral pattern (Ulam variation).
 */
public final class SpiralCalculator {

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Constants and Static Utilities
     * ────────────────────────────────────────────────────────────────────────────*/

    /**
     * A simple 2D integer coordinate container.
     */
    public record IntPoint(int x, int z) {}

    private SpiralCalculator() {}

    /**
     * Calculates the unit grid coordinate for a given index.
     * Index 0 returns (0,0), then spirals outwards: (1,0), (1,1), (0,1), (-1,1)...
     *
     * @param index The spiral index to calculate.
     * @return An IntPoint representing the unit coordinate.
     */
    public static IntPoint unitForIndex(int index) {
        // --- Early Exit ---
        if (index <= 0) {
            return new IntPoint(0, 0);
        }

        // --- Ring Calculation ---
        long n = index;
        long r = (long) Math.ceil((Math.sqrt(n + 1d) - 1d) / 2d); // current ring radius
        long side = 2L * r;
        long max = (2L * r + 1);
        max = max * max - 1; // max index on this ring at (r, -r)

        // distance backwards from the max index point on the ring
        long d = max - n;

        // --- Perimeter Mapping ---
        long x, z;
        if (d <= side) {                 // Bottom edge: (r,-r) -> (-r,-r)
            x = r - d;
            z = -r;
        } else if (d <= 2 * side) {      // Left edge: (-r,-r) -> (-r,r)
            x = -r;
            z = -r + (d - side);
        } else if (d <= 3 * side) {      // Top edge: (-r,r) -> (r,r)
            x = -r + (d - 2 * side);
            z = r;
        } else {                         // Right edge: (r,r) -> (r,-r+1)
            x = r;
            z = r - (d - 3 * side);
        }

        return new IntPoint((int) x, (int) z);
    }

    /**
     * Scales a unit spiral coordinate into a Minecraft ChunkPos.
     *
     * @param index         The spiral index.
     * @param spacingChunks Distance between points in chunks.
     * @param centerOffsetX Global X offset for the spiral center.
     * @param centerOffsetZ Global Z offset for the spiral center.
     * @return A ChunkPos representing the scaled target.
     */
    public static ChunkPos chunkForIndex(int index, int spacingChunks, int centerOffsetX, int centerOffsetZ) {
        IntPoint p = unitForIndex(index);

        long cx = (long) p.x() * (long) spacingChunks + (long) centerOffsetX;
        long cz = (long) p.z() * (long) spacingChunks + (long) centerOffsetZ;

        return new ChunkPos((int) cx, (int) cz);
    }
}