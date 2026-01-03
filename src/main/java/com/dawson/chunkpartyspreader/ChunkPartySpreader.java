package com.dawson.chunkpartyspreader;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Chunk Party Spreader
 *
 * Server-side utility mod. Core logic will be registered on the Forge event bus in later steps.
 */
@Mod(ChunkPartySpreader.MODID)
public class ChunkPartySpreader {
    public static final String MODID = "chunkpartyspreader";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ChunkPartySpreader() {
        // Intentionally minimal for initial project setup.
    }
}
