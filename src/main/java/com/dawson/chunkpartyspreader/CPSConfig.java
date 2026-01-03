package com.dawson.chunkpartyspreader;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Defines the common configuration settings for the Chunk Party Spreader.
 * These settings are stored in 'chunkpartyspreader-common.toml'.
 */
public final class CPSConfig {

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Configuration Specifications
     * ────────────────────────────────────────────────────────────────────────────*/

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // --- Configuration Values ---

    /**
     * Distance between spiral points in chunks.
     */
    public static final ForgeConfigSpec.IntValue GRID_SPACING_CHUNKS = BUILDER
            .comment("Distance between spiral points in chunks (25 = 400 blocks).")
            .defineInRange("grid_spacing_chunks", 25, 1, Integer.MAX_VALUE);

    /**
     * If true, the algorithm will skip coordinates that land in ocean biomes.
     */
    public static final ForgeConfigSpec.BooleanValue SKIP_OCEANS = BUILDER
            .comment("If true, discard coordinates that land in an ocean biome.")
            .define("skip_oceans", true);

    /**
     * The command executed to pre-generate chunks via the Chunk By Chunk mod.
     */
    public static final ForgeConfigSpec.ConfigValue<String> GENERATION_COMMAND = BUILDER
            .comment("Command to execute for chunk pre-generation.",
                    "Use %d placeholders for BLOCK X and BLOCK Z (Center of the chunk).",
                    "Default uses /execute positioned to force the mod to spawn the chunk at that location.")
            .define("generation_command", "/execute in minecraft:overworld positioned %d 0 %d run chunkbychunk:spawnChunk");

    /**
     * Horizontal offset for the center of the spiral.
     */
    public static final ForgeConfigSpec.IntValue CENTER_OFFSET_X = BUILDER
            .comment("Center X offset (in chunks) for the spiral.")
            .defineInRange("center_offset_x", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);

    /**
     * Vertical (Z) offset for the center of the spiral.
     */
    public static final ForgeConfigSpec.IntValue CENTER_OFFSET_Z = BUILDER
            .comment("Center Z offset (in chunks) for the spiral.")
            .defineInRange("center_offset_z", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);

    /**
     * The built configuration specification.
     * MUST be defined AFTER all the configuration values above, or the spec will be empty.
     */
    public static final ForgeConfigSpec SPEC = BUILDER.build();

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Constructors
     * ────────────────────────────────────────────────────────────────────────────*/

    private CPSConfig() {}
}