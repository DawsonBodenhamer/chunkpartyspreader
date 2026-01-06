# Technical Specification: Chunk Party Spreader

**Target Platform:** Minecraft 1.20.1 (Forge)
**Recommended Forge Version:** 47.1.3+ (Targeting 47.4.13)
**Mod ID:** `chunkpartyspreader`
**Dependencies:** `chunkbychunk`

---

## 1. Architectural Overview

The **Chunk Party Spreader** (CPS) is a server-side utility mod designed to manage the distribution of players in a "Void World" scenario. It functions as a state machine that tracks the number of unique joins and assigns each new player a coordinate based on a growing square spiral pattern.

To ensure safety and performance, CPS implements a **Stasis System**:
1.  **Interception:** New players are immediately suspended in the sky (Y=320) with gravity disabled.
2.  **Generation:** The mod triggers the **Chunk By Chunk** mod via direct API integration to generate the assigned chunk.
3.  **Polling:** A server-tick listener monitors the chunk's generation status.
4.  **Release:** Once solid ground is detected, gravity is restored, and the player is safely lowered.

Additionally, the mod enforces geographic rules (skipping Oceans/Rivers), manages persistence via standard Forge `SavedData`, and includes a suite of simulation commands for logic testing without multiplayer clients.

---

## 2. Core Data Structures & Persistence

To ensure the "Spiral Index" and player assignments survive server restarts and crashes, the mod utilizes Forge's standard `SavedData` system attached to the Overworld (`Level.OVERWORLD`).

### 2.1. Class: `SpreaderWorldData`
Extends `net.minecraft.world.level.saveddata.SavedData`.

**Fields:**
*   `private int currentSpiralIndex`: Tracks the global counter for the next available slot in the spiral. Defaults to `0`.
*   `private Map<UUID, BlockPos> playerAssignments`: A persistent map linking a Player's UUID to their specific assigned "Home Chunk" center block.

**Methods:**
*   `save(CompoundTag tag)`: Serializes the index and the UUID map to NBT.
*   `load(CompoundTag tag)`: Deserializes the NBT back into memory.
*   `get(ServerLevel level)`: Static helper to retrieve or create the data from `DimensionDataStorage`.
*   `reset()`: Debug helper that clears all assignments and resets the index to 0.

**NBT Structure:**
```json
{
  "SpiralIndex": 12,
  "Assignments": [
    { "UUID": "c06f8906-...", "X": 100, "Y": 320, "Z": 100 },
    { "UUID": "...", "X": -400, "Y": 320, "Z": 100 }
  ]
}
```

---

## 3. Configuration (`chunkpartyspreader-common.toml`)

Using standard Forge Config.

*   `grid_spacing_chunks` (Integer, Default: `25`): The distance between spiral points in chunks. (25 chunks * 16 blocks = 400 blocks).
*   `skip_oceans` (Boolean, Default: `true`): If true, the algorithm discards coordinates that land in **Ocean** or **River** biomes to ensure dry land.
*   `generation_command` (String): *Legacy/Fallback.* The command string config exists, but proved unreliable, so the mod primarily uses direct API integration (`ChunkByChunkCompat`) if the dependency is loaded.
*   `center_offset_x` (Integer, Default: `0`): The X coordinate (in chunks) for the center of the spiral.
*   `center_offset_z` (Integer, Default: `0`): The Z coordinate (in chunks) for the center of the spiral.

---

## 4. Algorithms & Logic Flow

### 4.1. The Spiral Algorithm (Ulam Variation)
A utility class `SpiralCalculator` handles the math.
*   **Input:** `index` (int).
*   **Output:** `ChunkPos` (x, z).
*   **Logic:**
    1.  Convert `index` to a coordinate on a unit grid (1, 2, 3...) moving Right, Down, Left, Up.
    2.  Multiply unit coordinates by `grid_spacing_chunks`.
    3.  Add `center_offset`.

### 4.2. Biome Validation (Hydrophobic Check)
Before assigning a spot:
1.  Calculate target `ChunkPos` from the Spiral Algorithm.
2.  Convert to world `BlockPos` (center of chunk).
3.  Query `ServerLevel.getBiome(pos)`.
4.  Check `biome.is(BiomeTags.IS_OCEAN)` OR `biome.is(BiomeTags.IS_RIVER)`.
5.  **If Water:** Increment `currentSpiralIndex`, `markDirty()`, and loop to the next index.
6.  **If Safe:** Lock this index, save data, and proceed to assignment.

### 4.3. The First-Join Stasis Workflow
**Event:** `PlayerEvent.PlayerLoggedInEvent`

1.  **Check Data:** Does `playerAssignments` contain the player's UUID?
    *   **Yes:** If player has `cps_waiting_for_chunk` tag, resume polling. Otherwise, do nothing.
    *   **No:** Proceed to Assignment.
2.  **Assignment & Stasis:**
    *   Run **Spiral Algorithm** + **Biome Validation** to find `targetChunkPos`.
    *   Store UUID -> Pos mapping in `SpreaderWorldData`.
    *   Force load the chunk via `TicketType.PLAYER`.
    *   **Teleport:** Move player to `targetX, 320, targetZ`.
    *   **State:** Set `NoGravity = true` and add tag `cps_waiting_for_chunk`.
    *   **Generation:** Invoke `ChunkByChunkCompat.generateChunk()` (Direct API).
    *   Add player to `PENDING_TARGETS` map.

### 4.4. The Stasis Polling Loop
**Event:** `TickEvent.ServerTickEvent` (End Phase)

1.  Iterate through `PENDING_TARGETS`.
2.  Check every 20 ticks (1 second).
3.  **Ground Detection:** Scan from Y=Max down to Y=Min in the target chunk.
    *   If non-air/non-leaf block found: Increment `stabilityCounter`.
    *   If void: Reset counter.
4.  **Release:** If `stabilityCounter >= 3`:
    *   Teleport player to `GroundY + 1`.
    *   Remove `NoGravity`.
    *   Remove `cps_waiting_for_chunk` tag.
    *   Set Respawn Point to this location.
    *   Remove Chunk Ticket.

### 4.5. Respawn & Safety Logic
**Event:** `PlayerEvent.PlayerRespawnEvent` & `PlayerSetSpawnEvent`

*   **Respawn Fallback:** The mod hooks into the respawn event to validate the player's spawn point.
    *   If the player has a valid bed/anchor, they respawn there normally.
    *   If the bed/anchor is missing or invalid, the mod intercepts the respawn and sends them to their stored `SpreaderWorldData` assignment instead of World Spawn.
*   **Void Safety Platform:** On login/respawn, if the player is not in stasis but is over void (Y < MinBuildHeight), a 3x3 stone platform is generated under them to prevent death loops.
---

## 5. Implementation Steps (Developer Checklist)

1.  **Project Setup & Build:**
    *   Initialize Forge 1.20.1.
    *   Configure `build.gradle` to output jars as `modid-mcversion-modversion.jar`.
    *   Set license to **MIT**.

2.  **Data Layer:**
    *   Implement `SpreaderWorldData` with `reset()` capability.
    *   Implement `ChunkByChunkCompat` for reflection-based API access (isolating soft dependency).
    *   Implement `SpreaderSpawnFixes` for bed/void logic.

3.  **Logic Integration:**
    *   Implement `SpreaderEvents` handling `PlayerLoggedInEvent` and `ServerTickEvent` (Stasis Machine).
    *   Implement `SpiralCalculator` with River/Ocean checking.
    *   **CBC Fix:** Disable CBC's default "Initial Chunks" on server start to prevent ghost chunks at 0,0.

4.  **Simulation & Debugging Tools:**
    *   Implement `DebugCommands` class registering `/cps_sim`.
    *   **Features:**
        *   `join_fake <name>`: Uses `FakePlayerFactory` to simulate logic.
        *   `reset_data`: Wipes persistence for testing.
        *   `status`: Reports current index.

5.  **Testing Strategy:**
    *   **Method:** Use `/cps_sim join_fake` to simulate 5-10 joins.
    *   **Verify:**
        *   Spiral increments correctly.
        *   Oceans/Rivers are skipped (Log verification).
        *   Persistence works (Running same name twice results in same chunk).

---

## 6. Potential Edge Cases & Mitigations

*   **Chunk By Chunk Missing:**
    *   *Mitigation:* `ChunkByChunkCompat` checks `ModList.get().isLoaded`. If missing, logic proceeds but skips the generation call. Player waits in stasis until vanilla generation catches up (or hangs indefinitely if void world).
*   **River/Ocean Spam:**
    *   *Mitigation:* The loop has a hard cap of 10,000 attempts. If no valid chunk is found, it falls back to the current index to prevent server freeze.
*   **Mod Updates/Removal:**
    *   *Mitigation:* Data is stored in standard NBT (`data/chunkpartyspreader.dat`). If the mod is removed, players stay in their chunks, but new players spawn at world spawn.
*   **Fake Player Teleportation:**
    *   *Edge Case:* `FakePlayer` entities do not process network packets.
    *   *Mitigation:* `DebugCommands` manually updates the FakePlayer's position object so coordinate reports in chat remain accurate during simulation.
---

# Project Files

## CPS Provided Code

### 📂 `Repository Root/`
`README.md`
```markdown
# Chunk Party Spreader (CPS)

Welcome to **Chunk Party Spreader**, the server-side utility that turns your empty void world into an organized, sprawling suburbia.

Instead of piling everyone onto a single chaotic spawn island, CPS acts as a socially anxious bouncer. It assigns every new player their own dedicated chunk in a growing square spiral pattern. It even politely asks the **Chunk By Chunk** mod to generate the land *before* they arrive, so they don't fall into the abyss while loading.

## Key Features
*   **The Spiral:** Assigns home chunks in a deterministic Ulam Spiral pattern (0,0 -> 1,0 -> 1,1 -> ...).
*   **Void Stasis:** Catches players on join, floats them safely in the sky (Y=320), and only lets them drop once their land is generated and solid.
*   **Hydrophobic Logic:** (Configurable) If a player's assigned coordinate lands in an **Ocean** or **River**, the mod says "Absolutely not," skips that index, and finds them dry land.
*   **Persistence:** Remembers exactly where every player "lives." If they die without a bed, they respawn at their personal chunk, not world spawn.
*   **Chunk By Chunk Integration:** Directly interfaces with CBC to trigger single-chunk generation commands.

---

## 🛠️ Configuration
Found in `config/chunkpartyspreader-common.toml`.

| Setting | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `grid_spacing_chunks` | Int | `25` | The distance between player chunks. <br>*(25 chunks = 400 blocks)*. |
| `skip_oceans` | Bool | `true` | If `true`, avoids `minecraft:ocean` and `minecraft:river` biomes. |
| `center_offset_x` | Int | `0` | Offsets the center of the spiral on the X axis (in chunks). |
| `center_offset_z` | Int | `0` | Offsets the center of the spiral on the Z axis (in chunks). |
| `generation_command` | String | *See Config* | The command run to generate the chunk. Defaults to CBC's spawn command. |

---

## 🧪 Simulation & Testing Guide
**"Do I need 50 friends to test this?"**
No. You have us.

Included a suite of **Simulation Commands** (`/cps_sim`) that allow you to fake the entire join process without needing a single other person online.

### Prerequisites
*   You must have **OP (Level 2+)** permissions.
*   It is recommended to watch the server console (or chat) for the logs.

### Command Reference

#### 1. Simulate a Player Join
` /cps_sim join_fake <Name>`

**What it does:**
Creates a virtual player entity with the given name (e.g., `TesterBob`), generates a real UUID for them, and runs them through the entire Mod Logic (Assignment -> Generation -> Stasis Check).

**How to use it:**
1.  Run `/cps_sim join_fake UserOne`.
2.  Watch the chat. It will tell you:
    *   If the index skips (e.g., jumps from 4 to 6), the mod successfully detected a River or Ocean at index 5.
    *   Verify the report says `Stasis Mode: ACTIVE`.
3.  Run `/cps_sim join_fake UserTwo`.
    *   Verify they got a *different* chunk than UserOne.

#### 2. Check Persistence (The "Re-Join" Test)
**What it does:**
Verifies that the mod remembers a player who has already joined.

**How to use it:**
1.  Run `/cps_sim join_fake UserOne`. (Note the coordinate, e.g., `0, 320, 0`).
2.  Run `/cps_sim join_fake UserOne` **again**.
3.  **Pass Condition:** The mod should report `✔ Assignment Exists` at the *same* coordinate (`0, 320, 0`).

#### 3. Reset Data (The "Wipe" Button)
` /cps_sim reset_data`

**What it does:**
**DANGER:** Nukes all saved mod data.
*   Resets the Spiral Index to 0.
*   Deletes all player home assignments.
*   Clears the persistence file.

**Use this when:** You want to restart a test session from scratch without restarting the entire server.

#### 4. Check Status
` /cps_sim status`

**What it does:**
Simply prints the current Spiral Index (how many spots have been taken).

---

## Dependencies
*   **Minecraft:** 1.20.1
*   **Forge:** 47.4.13+
*   **Chunk By Chunk:** Optional, but **Highly Recommended**.
    *   *Without CBC:* The mod will still teleport players, but the chunks will be empty void until the server naturally generates them (which might lag or kill the player).
    *   *With CBC:* The mod triggers the specific generation command defined in the config.

---

## License
This mod is licensed under the MIT License - see the [LICENSE.txt](LICENSE.txt) file for details.
```

`CHANGELOG.md`
```markdown
## [0.0.0] - YYYY-MM-DD

### Added

### Changed

### Fixed
```

`build.gradle`
```groovy
// --- 1. Plugins and Toolchain ---
plugins {
    id 'eclipse'
    id 'idea'
    id 'maven-publish'
    id 'net.minecraftforge.gradle' version '[6.0,6.2)'
}

version = mod_version
group = mod_group_id

base {
    archivesName = "${mod_id}-${minecraft_version}"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

// --- 2. Minecraft Configuration ---
minecraft {
    mappings channel: mapping_channel, version: mapping_version
    copyIdeResources = true

    runs {
        configureEach {
            workingDirectory project.file('run')

            property 'forge.logging.markers', 'REGISTRIES'
            property 'forge.logging.console.level', 'debug'

            property 'mixin.env.remapRefMap', 'true'
            property 'mixin.env.refMapRemappingEnv', 'searge'
            property 'mixin.env.refMapRemappingFile', file("$buildDir/createSrgToMcp/output.srg").absolutePath
        }

        client {
            mods {
                "${mod_id}" { source sourceSets.main }
            }
        }

        server {
            args '--nogui'
            mods {
                "${mod_id}" { source sourceSets.main }
            }
        }

        gameTestServer {
            args '--nogui'
            mods {
                "${mod_id}" { source sourceSets.main }
            }
        }

        data {
            args '--mod', mod_id, '--all', '--output', file('src/generated/resources/'), '--existing', file('src/main/resources/')
            mods {
                "${mod_id}" { source sourceSets.main }
            }
        }
    }
}

// --- 3. Dependencies and Repositories ---
sourceSets.main.resources { srcDir 'src/generated/resources' }

repositories {
    mavenCentral()
    maven { url = "https://cursemaven.com" }
}

dependencies {
    minecraft "net.minecraftforge:forge:${minecraft_version}-${forge_version}"
    implementation fg.deobf("curse.maven:chunk-by-chunk-565866:5168269")
}

// --- 4. Task Configurations ---
tasks.named('processResources', ProcessResources).configure {
    def replaceProperties = [
            minecraft_version      : minecraft_version,
            minecraft_version_range: minecraft_version_range,
            forge_version          : forge_version,
            forge_version_range    : forge_version_range,
            loader_version_range   : loader_version_range,
            mod_id                 : mod_id,
            mod_name               : mod_name,
            mod_license            : mod_license,
            mod_version            : mod_version,
            mod_authors            : mod_authors,
            mod_description        : mod_description
    ]

    inputs.properties replaceProperties

    filesMatching(['META-INF/mods.toml', 'pack.mcmeta']) {
        expand replaceProperties
    }
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
}

jar {
    manifest {
        attributes([
                "Specification-Title"     : mod_id,
                "Specification-Vendor"    : mod_authors,
                "Specification-Version"   : "1",
                "Implementation-Title"    : project.name,
                "Implementation-Version"  : project.jar.archiveVersion,
                "Implementation-Vendor"   : mod_authors,
                "Implementation-Timestamp": new Date().format("yyyy-MM-dd'T'HH:mm:ssZ")
        ])
    }
}

jar.finalizedBy('reobfJar')

publishing {
    publications {
        register('mavenJava', MavenPublication) {
            artifact jar
        }
    }
    repositories {
        maven {
            url "file://${project.projectDir}/mcmodsrepo"
        }
    }
}
```

`gradle.properties`
```properties
# --- 1. Gradle Environment Settings ---
# Allocation for the Minecraft decompilation process.
org.gradle.jvmargs=-Xmx3G
org.gradle.daemon=false

# --- 2. Toolchain and Versioning ---
minecraft_version=1.20.1
minecraft_version_range=[1.20.1,1.21)
forge_version=47.4.13
forge_version_range=[47,)
loader_version_range=[47,)

# --- 3. Mapping Configuration ---
mapping_channel=official
mapping_version=1.20.1

# --- 4. Mod Metadata ---
mod_id=chunkpartyspreader
mod_name=Chunk Party Spreader
mod_license=MIT
mod_version=1.0.0
mod_group_id=com.dawson.chunkpartyspreader
mod_authors=Dawson Bodenhamer (The Scarlet Fox)
mod_description=Server-side utility mod that assigns each new player a unique home chunk in a spiral pattern and optionally pre-generates it via Chunk By Chunk.
```

`LICENSE.txt`
```text
MIT License

Copyright (c) 2026 Dawson Bodenhamer
www.ForTheKing.Design

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR
A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

`settings.gradle`
```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = 'MinecraftForge'
            url = 'https://maven.minecraftforge.net/'
        }
    }
}

plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '0.7.0'
}

rootProject.name = 'chunkpartyspreader'

```

`CREDITS.txt`
```text
Chunk Party Spreader
=================================================================
Author:
    Dawson Bodenhamer
    www.ForTheKing.Design

Commissioned By:
    Jessica Osio

Special Thanks:
  The Forge Team - For the modding platform.
  dividesBy0 - For the 'Chunk By Chunk' mod (soft dependency).

License:
  MIT License (See LICENSE.txt)
```

### 📂 `src/main/java/com/dawson/chunkpartyspreader/`
`ChunkByChunkCompat.java`
```java
package com.dawson.chunkpartyspreader;

// (Imports omitted to save token count)

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

        // 1. Verify actually running on a CBC world
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

        // 2. Extract existing settings to prevent breaking the user's config
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
```

`ChunkPartySpreader.java`
```java
package com.dawson.chunkpartyspreader;

// (Imports omitted to save token count)

/**
 * The main entry point for the Chunk Party Spreader mod.
 * Handles initial setup and configuration registration.
 */
@Mod(ChunkPartySpreader.MODID)
@SuppressWarnings("removal")
public class ChunkPartySpreader {

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Constants and Static Utilities
     * ────────────────────────────────────────────────────────────────────────────*/

    public static final String MODID = "chunkpartyspreader";
    public static final Logger LOGGER = LogUtils.getLogger();

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Constructors
     * ────────────────────────────────────────────────────────────────────────────*/

    /**
     * Initializes the mod and registers the common configuration file.
     */
    public ChunkPartySpreader() {
        // Register the Forge config specification.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CPSConfig.SPEC);
    }
}
```

`CPSConfig.java`
```java
package com.dawson.chunkpartyspreader;

// (Imports omitted to save token count)

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
```

`DebugCommands.java`
```java
package com.dawson.chunkpartyspreader;

// (Imports omitted to save token count)

/**
 * Provides the /cps_sim command suite for synthetic player testing.
 */
@Mod.EventBusSubscriber(modid = ChunkPartySpreader.MODID)
public class DebugCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cps_sim")
                .requires(s -> s.hasPermission(2)) // OPs only

                // Sub-command: /cps_sim join_fake <name>
                .then(Commands.literal("join_fake")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(DebugCommands::simulateJoin)))

                // Sub-command: /cps_sim reset_data
                .then(Commands.literal("reset_data")
                        .executes(DebugCommands::resetData))

                // Sub-command: /cps_sim status
                .then(Commands.literal("status")
                        .executes(DebugCommands::status))
        );
    }

    private static int simulateJoin(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        ServerLevel level = context.getSource().getLevel();

        // Generate a consistent UUID based on the name to test persistence
        UUID fakeId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(fakeId, name);

        // Create the synthetic player
        ServerPlayer fakePlayer = FakePlayerFactory.get(level, profile);

        // Force the fake player to the origin initially to ensure we aren't just seeing the command author's coords
        fakePlayer.setPosRaw(0, 100, 0);

        context.getSource().sendSuccess(() ->
                Component.literal("--------------------------------------------------").withStyle(ChatFormatting.GOLD), false);
        context.getSource().sendSuccess(() ->
                Component.literal("Simulating Join for: " + name).withStyle(ChatFormatting.YELLOW), false);
        context.getSource().sendSuccess(() ->
                Component.literal("UUID: " + fakeId).withStyle(ChatFormatting.GRAY), false);

        // --- EXECUTE LOGIC ---
        SpreaderEvents.processPlayerJoin(fakePlayer);

        // --- REPORT RESULTS ---
        SpreaderWorldData data = SpreaderWorldData.get(level);
        BlockPos assignment = data.getAssignment(fakeId);
        boolean isPending = SpreaderEvents.isPending(fakeId);

        if (assignment != null) {
            context.getSource().sendSuccess(() ->
                    Component.literal("✔ Assignment Exists: " + assignment.toShortString())
                            .withStyle(ChatFormatting.GREEN), false);

            // Since the player is a FakePlayer, teleportTo doesn't update position immediately,
            // so manually update it here just for the sake of the report matching the logic.
            fakePlayer.setPosRaw(assignment.getX() + 0.5, 320, assignment.getZ() + 0.5);

            String loc = String.format("%.1f, %.1f, %.1f", fakePlayer.getX(), fakePlayer.getY(), fakePlayer.getZ());
            context.getSource().sendSuccess(() ->
                    Component.literal("✔ Entity Location: " + loc).withStyle(ChatFormatting.AQUA), false);
        } else {
            context.getSource().sendSuccess(() ->
                    Component.literal("✘ Failed to assign home chunk.").withStyle(ChatFormatting.RED), false);
        }

        if (isPending) {
            context.getSource().sendSuccess(() ->
                    Component.literal("✔ Stasis Mode: ACTIVE (Added to pending map)").withStyle(ChatFormatting.GREEN), false);
        } else {
            context.getSource().sendSuccess(() ->
                    Component.literal("ℹ Stasis Mode: INACTIVE (Player was not added to wait list)").withStyle(ChatFormatting.YELLOW), false);
        }

        context.getSource().sendSuccess(() ->
                Component.literal("--------------------------------------------------").withStyle(ChatFormatting.GOLD), false);

        return 1;
    }

    private static int resetData(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        SpreaderWorldData.get(level).reset();
        context.getSource().sendSuccess(() ->
                Component.literal("CPS Data has been wiped. Spiral Index reset to 0.").withStyle(ChatFormatting.RED), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        SpreaderWorldData data = SpreaderWorldData.get(level);
        int idx = data.getCurrentSpiralIndex();

        context.getSource().sendSuccess(() ->
                Component.literal("Current Spiral Index: " + idx).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }
}
```

`SpiralCalculator.java`
```java
package com.dawson.chunkpartyspreader;

// (Imports omitted to save token count)

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
```

`SpiralCalculatorTestMain.java`
```java
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
```

`SpreaderEvents.java`
```java
package com.dawson.chunkpartyspreader;

// (Imports omitted to save token count)

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
```

`SpreaderSpawnFixes.java`
```java
package com.dawson.chunkpartyspreader;

// (Imports omitted to save token count)

@Mod.EventBusSubscriber(modid = ChunkPartySpreader.MODID)
public final class SpreaderSpawnFixes {

    private SpreaderSpawnFixes() {}

    // Guard to prevent infinite recursion when re-firing the set spawn event
    private static final ThreadLocal<Boolean> IS_ADJUSTING_SPAWN = ThreadLocal.withInitial(() -> false);

    /* ──────────────────────────────────────────────────────────────────────────────
     *        Event Handlers
     * ────────────────────────────────────────────────────────────────────────────*/

    // --- Void Safety Platform Checks ---

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
        // Do not interfere. SpreaderEvents has them floating safely at Y=320 with NoGravity.
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
```

`SpreaderWorldData.java`
```java
package com.dawson.chunkpartyspreader;

// (Imports omitted to save token count)

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
```

### 📂 `src/main/resources/`
`pack.mcmeta`
```json
{
  "pack": {
    "description": {
      "text": "${mod_name} resources"
    },
    "pack_format": 15
  }
}
```

### 📂 `src/main/resources/META-INF/`
`mods.toml`
```toml
modLoader="javafml"
loaderVersion="${loader_version_range}"
license="${mod_license}"
displayTest="IGNORE_SERVER_VERSION"

[[mods]]
modId="${mod_id}"
version="${mod_version}"
displayName="${mod_name}"
authors="${mod_authors}"
logoFile="chunk_party_spreader_icon.png"

description='''${mod_description}'''

# --- Dependencies ---

[[dependencies."${mod_id}"]]
modId="forge"
mandatory=true
versionRange="${forge_version_range}"
ordering="NONE"
side="BOTH"

[[dependencies."${mod_id}"]]
modId="minecraft"
mandatory=true
versionRange="${minecraft_version_range}"
ordering="NONE"
side="BOTH"

[[dependencies."${mod_id}"]]
modId="chunkbychunk"
mandatory=false
versionRange="[0,)"
ordering="AFTER"
side="SERVER"
```

### 📂 `src/main/resources/`
`chunk_party_spreader_icon.png`

End CPS Provided Code

---