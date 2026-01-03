package com.dawson.chunkpartyspreader;

/**
 * Standalone test utility to verify the SpiralCalculator logic without launching Minecraft.
 */
public final class SpiralCalculatorTestMain {

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Constructors
     * ────────────────────────────────────────────────────────────────────────────*/

    private SpiralCalculatorTestMain() {}

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Public Methods
     * ────────────────────────────────────────────────────────────────────────────*/

    /**
     * Iterates through the first 25 indices and prints the calculated unit coordinates to the console.
     */
    public static void main(String[] args) {
        // --- Algorithm Verification ---
        for (int i = 0; i < 25; i++) {
            var p = SpiralCalculator.unitForIndex(i);
            System.out.printf("%d -> (%d,%d)%n", i, p.x(), p.z());
        }
    }
}