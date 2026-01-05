# Technical Specification: Chunk Party Spreader

**Target Platform:** Minecraft 1.20.1 (Forge)
**Recommended Forge Version:** 47.1.3+ (Targeting 47.4.13)
**Mod ID:** `chunkpartyspreader` (Proposed)
**Dependencies:** `chunkbychunk` (Required for generation command)

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

*   **Bed Persistence:** If a player sets their spawn (Bed/Anchor), the mod intercepts the event to ensure `forced = true`, preventing "Missing Bed" errors from resetting them to world spawn.
*   **Respawn Fallback:** If a player dies without a bed, the mod intercepts the respawn and sends them to their stored `SpreaderWorldData` assignment instead of (0,0).
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
    archivesName = mod_id
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
mod_license=All Rights Reserved
mod_version=1.0.0
mod_group_id=com.dawson.chunkpartyspreader
mod_authors=Dawson
mod_description=Server-side utility mod that assigns each new player a unique home chunk in a spiral pattern and optionally pre-generates it via Chunk By Chunk.
```

`LICENSE.txt`
```text
Unless noted below, Minecraft Forge, Forge Mod Loader, and all 
parts herein are licensed under the terms of the LGPL 2.1 found
here http://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt and 
copied below.

Homepage: http://minecraftforge.net/
          https://github.com/MinecraftForge/MinecraftForge
          

A note on authorship:
All source artifacts are property of their original author, with
the exclusion of the contents of the patches directory and others
copied from it from time to time. Authorship of the contents of
the patches directory is retained by the Minecraft Forge project.
This is because the patches are partially machine generated
artifacts, and are changed heavily due to the way forge works.
Individual attribution within them is impossible.

Consent:
All contributions to Forge must consent to the release of any
patch content to the Forge project.

A note on infectivity:
The LGPL is chosen specifically so that projects may depend on Forge
features without being infected with its license. That is the 
purpose of the LGPL. Mods and others using this code via ordinary
Java mechanics for referencing libraries are specifically not bound
by Forge's license for the Mod code.


=== MCP Data ===
This software includes data from the Minecraft Coder Pack (MCP), with kind permission
from them. The license to MCP data is not transitive - distribution of this data by
third parties requires independent licensing from the MCP team. This data is not
redistributable without permission from the MCP team.

=== Sharing ===
I grant permission for some parts of FML to be redistributed outside the terms of the LGPL, for the benefit of
the minecraft modding community. All contributions to these parts should be licensed under the same additional grant.

-- Runtime patcher --
License is granted to redistribute the runtime patcher code (src/main/java/net/minecraftforge/fml/common/patcher
and subdirectories) under any alternative open source license as classified by the OSI (http://opensource.org/licenses)

-- ASM transformers --
License is granted to redistribute the ASM transformer code (src/main/java/net/minecraftforge/common/asm/ and subdirectories)
under any alternative open source license as classified by the OSI (http://opensource.org/licenses)

=========================================================================
This software includes portions from the Apache Maven project at
http://maven.apache.org/ specifically the ComparableVersion.java code. It is
included based on guidelines at
http://www.softwarefreedom.org/resources/2007/gpl-non-gpl-collaboration.html
with notices intact. The only change is a non-functional change of package name.

This software contains a partial repackaging of javaxdelta, a BSD licensed program for generating
binary differences and applying them, sourced from the subversion at http://sourceforge.net/projects/javaxdelta/
authored by genman, heikok, pivot.
The only changes are to replace some Trove collection types with standard Java collections, and repackaged.

This software includes the Monocraft font from https://github.com/IdreesInc/Monocraft/ for use in the early loading
display.
=========================================================================


                  GNU LESSER GENERAL PUBLIC LICENSE
                       Version 2.1, February 1999

 Copyright (C) 1991, 1999 Free Software Foundation, Inc.
 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 Everyone is permitted to copy and distribute verbatim copies
 of this license document, but changing it is not allowed.

[This is the first released version of the Lesser GPL.  It also counts
 as the successor of the GNU Library Public License, version 2, hence
 the version number 2.1.]

                            Preamble

  The licenses for most software are designed to take away your
freedom to share and change it.  By contrast, the GNU General Public
Licenses are intended to guarantee your freedom to share and change
free software--to make sure the software is free for all its users.

  This license, the Lesser General Public License, applies to some
specially designated software packages--typically libraries--of the
Free Software Foundation and other authors who decide to use it.  You
can use it too, but we suggest you first think carefully about whether
this license or the ordinary General Public License is the better
strategy to use in any particular case, based on the explanations below.

  When we speak of free software, we are referring to freedom of use,
not price.  Our General Public Licenses are designed to make sure that
you have the freedom to distribute copies of free software (and charge
for this service if you wish); that you receive source code or can get
it if you want it; that you can change the software and use pieces of
it in new free programs; and that you are informed that you can do
these things.

  To protect your rights, we need to make restrictions that forbid
distributors to deny you these rights or to ask you to surrender these
rights.  These restrictions translate to certain responsibilities for
you if you distribute copies of the library or if you modify it.

  For example, if you distribute copies of the library, whether gratis
or for a fee, you must give the recipients all the rights that we gave
you.  You must make sure that they, too, receive or can get the source
code.  If you link other code with the library, you must provide
complete object files to the recipients, so that they can relink them
with the library after making changes to the library and recompiling
it.  And you must show them these terms so they know their rights.

  We protect your rights with a two-step method: (1) we copyright the
library, and (2) we offer you this license, which gives you legal
permission to copy, distribute and/or modify the library.

  To protect each distributor, we want to make it very clear that
there is no warranty for the free library.  Also, if the library is
modified by someone else and passed on, the recipients should know
that what they have is not the original version, so that the original
author's reputation will not be affected by problems that might be
introduced by others.

  Finally, software patents pose a constant threat to the existence of
any free program.  We wish to make sure that a company cannot
effectively restrict the users of a free program by obtaining a
restrictive license from a patent holder.  Therefore, we insist that
any patent license obtained for a version of the library must be
consistent with the full freedom of use specified in this license.

  Most GNU software, including some libraries, is covered by the
ordinary GNU General Public License.  This license, the GNU Lesser
General Public License, applies to certain designated libraries, and
is quite different from the ordinary General Public License.  We use
this license for certain libraries in order to permit linking those
libraries into non-free programs.

  When a program is linked with a library, whether statically or using
a shared library, the combination of the two is legally speaking a
combined work, a derivative of the original library.  The ordinary
General Public License therefore permits such linking only if the
entire combination fits its criteria of freedom.  The Lesser General
Public License permits more lax criteria for linking other code with
the library.

  We call this license the "Lesser" General Public License because it
does Less to protect the user's freedom than the ordinary General
Public License.  It also provides other free software developers Less
of an advantage over competing non-free programs.  These disadvantages
are the reason we use the ordinary General Public License for many
libraries.  However, the Lesser license provides advantages in certain
special circumstances.

  For example, on rare occasions, there may be a special need to
encourage the widest possible use of a certain library, so that it becomes
a de-facto standard.  To achieve this, non-free programs must be
allowed to use the library.  A more frequent case is that a free
library does the same job as widely used non-free libraries.  In this
case, there is little to gain by limiting the free library to free
software only, so we use the Lesser General Public License.

  In other cases, permission to use a particular library in non-free
programs enables a greater number of people to use a large body of
free software.  For example, permission to use the GNU C Library in
non-free programs enables many more people to use the whole GNU
operating system, as well as its variant, the GNU/Linux operating
system.

  Although the Lesser General Public License is Less protective of the
users' freedom, it does ensure that the user of a program that is
linked with the Library has the freedom and the wherewithal to run
that program using a modified version of the Library.

  The precise terms and conditions for copying, distribution and
modification follow.  Pay close attention to the difference between a
"work based on the library" and a "work that uses the library".  The
former contains code derived from the library, whereas the latter must
be combined with the library in order to run.

                  GNU LESSER GENERAL PUBLIC LICENSE
   TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION

  0. This License Agreement applies to any software library or other
program which contains a notice placed by the copyright holder or
other authorized party saying it may be distributed under the terms of
this Lesser General Public License (also called "this License").
Each licensee is addressed as "you".

  A "library" means a collection of software functions and/or data
prepared so as to be conveniently linked with application programs
(which use some of those functions and data) to form executables.

  The "Library", below, refers to any such software library or work
which has been distributed under these terms.  A "work based on the
Library" means either the Library or any derivative work under
copyright law: that is to say, a work containing the Library or a
portion of it, either verbatim or with modifications and/or translated
straightforwardly into another language.  (Hereinafter, translation is
included without limitation in the term "modification".)

  "Source code" for a work means the preferred form of the work for
making modifications to it.  For a library, complete source code means
all the source code for all modules it contains, plus any associated
interface definition files, plus the scripts used to control compilation
and installation of the library.

  Activities other than copying, distribution and modification are not
covered by this License; they are outside its scope.  The act of
running a program using the Library is not restricted, and output from
such a program is covered only if its contents constitute a work based
on the Library (independent of the use of the Library in a tool for
writing it).  Whether that is true depends on what the Library does
and what the program that uses the Library does.

  1. You may copy and distribute verbatim copies of the Library's
complete source code as you receive it, in any medium, provided that
you conspicuously and appropriately publish on each copy an
appropriate copyright notice and disclaimer of warranty; keep intact
all the notices that refer to this License and to the absence of any
warranty; and distribute a copy of this License along with the
Library.

  You may charge a fee for the physical act of transferring a copy,
and you may at your option offer warranty protection in exchange for a
fee.

  2. You may modify your copy or copies of the Library or any portion
of it, thus forming a work based on the Library, and copy and
distribute such modifications or work under the terms of Section 1
above, provided that you also meet all of these conditions:

    a) The modified work must itself be a software library.

    b) You must cause the files modified to carry prominent notices
    stating that you changed the files and the date of any change.

    c) You must cause the whole of the work to be licensed at no
    charge to all third parties under the terms of this License.

    d) If a facility in the modified Library refers to a function or a
    table of data to be supplied by an application program that uses
    the facility, other than as an argument passed when the facility
    is invoked, then you must make a good faith effort to ensure that,
    in the event an application does not supply such function or
    table, the facility still operates, and performs whatever part of
    its purpose remains meaningful.

    (For example, a function in a library to compute square roots has
    a purpose that is entirely well-defined independent of the
    application.  Therefore, Subsection 2d requires that any
    application-supplied function or table used by this function must
    be optional: if the application does not supply it, the square
    root function must still compute square roots.)

These requirements apply to the modified work as a whole.  If
identifiable sections of that work are not derived from the Library,
and can be reasonably considered independent and separate works in
themselves, then this License, and its terms, do not apply to those
sections when you distribute them as separate works.  But when you
distribute the same sections as part of a whole which is a work based
on the Library, the distribution of the whole must be on the terms of
this License, whose permissions for other licensees extend to the
entire whole, and thus to each and every part regardless of who wrote
it.

Thus, it is not the intent of this section to claim rights or contest
your rights to work written entirely by you; rather, the intent is to
exercise the right to control the distribution of derivative or
collective works based on the Library.

In addition, mere aggregation of another work not based on the Library
with the Library (or with a work based on the Library) on a volume of
a storage or distribution medium does not bring the other work under
the scope of this License.

  3. You may opt to apply the terms of the ordinary GNU General Public
License instead of this License to a given copy of the Library.  To do
this, you must alter all the notices that refer to this License, so
that they refer to the ordinary GNU General Public License, version 2,
instead of to this License.  (If a newer version than version 2 of the
ordinary GNU General Public License has appeared, then you can specify
that version instead if you wish.)  Do not make any other change in
these notices.

  Once this change is made in a given copy, it is irreversible for
that copy, so the ordinary GNU General Public License applies to all
subsequent copies and derivative works made from that copy.

  This option is useful when you wish to copy part of the code of
the Library into a program that is not a library.

  4. You may copy and distribute the Library (or a portion or
derivative of it, under Section 2) in object code or executable form
under the terms of Sections 1 and 2 above provided that you accompany
it with the complete corresponding machine-readable source code, which
must be distributed under the terms of Sections 1 and 2 above on a
medium customarily used for software interchange.

  If distribution of object code is made by offering access to copy
from a designated place, then offering equivalent access to copy the
source code from the same place satisfies the requirement to
distribute the source code, even though third parties are not
compelled to copy the source along with the object code.

  5. A program that contains no derivative of any portion of the
Library, but is designed to work with the Library by being compiled or
linked with it, is called a "work that uses the Library".  Such a
work, in isolation, is not a derivative work of the Library, and
therefore falls outside the scope of this License.

  However, linking a "work that uses the Library" with the Library
creates an executable that is a derivative of the Library (because it
contains portions of the Library), rather than a "work that uses the
library".  The executable is therefore covered by this License.
Section 6 states terms for distribution of such executables.

  When a "work that uses the Library" uses material from a header file
that is part of the Library, the object code for the work may be a
derivative work of the Library even though the source code is not.
Whether this is true is especially significant if the work can be
linked without the Library, or if the work is itself a library.  The
threshold for this to be true is not precisely defined by law.

  If such an object file uses only numerical parameters, data
structure layouts and accessors, and small macros and small inline
functions (ten lines or less in length), then the use of the object
file is unrestricted, regardless of whether it is legally a derivative
work.  (Executables containing this object code plus portions of the
Library will still fall under Section 6.)

  Otherwise, if the work is a derivative of the Library, you may
distribute the object code for the work under the terms of Section 6.
Any executables containing that work also fall under Section 6,
whether or not they are linked directly with the Library itself.

  6. As an exception to the Sections above, you may also combine or
link a "work that uses the Library" with the Library to produce a
work containing portions of the Library, and distribute that work
under terms of your choice, provided that the terms permit
modification of the work for the customer's own use and reverse
engineering for debugging such modifications.

  You must give prominent notice with each copy of the work that the
Library is used in it and that the Library and its use are covered by
this License.  You must supply a copy of this License.  If the work
during execution displays copyright notices, you must include the
copyright notice for the Library among them, as well as a reference
directing the user to the copy of this License.  Also, you must do one
of these things:

    a) Accompany the work with the complete corresponding
    machine-readable source code for the Library including whatever
    changes were used in the work (which must be distributed under
    Sections 1 and 2 above); and, if the work is an executable linked
    with the Library, with the complete machine-readable "work that
    uses the Library", as object code and/or source code, so that the
    user can modify the Library and then relink to produce a modified
    executable containing the modified Library.  (It is understood
    that the user who changes the contents of definitions files in the
    Library will not necessarily be able to recompile the application
    to use the modified definitions.)

    b) Use a suitable shared library mechanism for linking with the
    Library.  A suitable mechanism is one that (1) uses at run time a
    copy of the library already present on the user's computer system,
    rather than copying library functions into the executable, and (2)
    will operate properly with a modified version of the library, if
    the user installs one, as long as the modified version is
    interface-compatible with the version that the work was made with.

    c) Accompany the work with a written offer, valid for at
    least three years, to give the same user the materials
    specified in Subsection 6a, above, for a charge no more
    than the cost of performing this distribution.

    d) If distribution of the work is made by offering access to copy
    from a designated place, offer equivalent access to copy the above
    specified materials from the same place.

    e) Verify that the user has already received a copy of these
    materials or that you have already sent this user a copy.

  For an executable, the required form of the "work that uses the
Library" must include any data and utility programs needed for
reproducing the executable from it.  However, as a special exception,
the materials to be distributed need not include anything that is
normally distributed (in either source or binary form) with the major
components (compiler, kernel, and so on) of the operating system on
which the executable runs, unless that component itself accompanies
the executable.

  It may happen that this requirement contradicts the license
restrictions of other proprietary libraries that do not normally
accompany the operating system.  Such a contradiction means you cannot
use both them and the Library together in an executable that you
distribute.

  7. You may place library facilities that are a work based on the
Library side-by-side in a single library together with other library
facilities not covered by this License, and distribute such a combined
library, provided that the separate distribution of the work based on
the Library and of the other library facilities is otherwise
permitted, and provided that you do these two things:

    a) Accompany the combined library with a copy of the same work
    based on the Library, uncombined with any other library
    facilities.  This must be distributed under the terms of the
    Sections above.

    b) Give prominent notice with the combined library of the fact
    that part of it is a work based on the Library, and explaining
    where to find the accompanying uncombined form of the same work.

  8. You may not copy, modify, sublicense, link with, or distribute
the Library except as expressly provided under this License.  Any
attempt otherwise to copy, modify, sublicense, link with, or
distribute the Library is void, and will automatically terminate your
rights under this License.  However, parties who have received copies,
or rights, from you under this License will not have their licenses
terminated so long as such parties remain in full compliance.

  9. You are not required to accept this License, since you have not
signed it.  However, nothing else grants you permission to modify or
distribute the Library or its derivative works.  These actions are
prohibited by law if you do not accept this License.  Therefore, by
modifying or distributing the Library (or any work based on the
Library), you indicate your acceptance of this License to do so, and
all its terms and conditions for copying, distributing or modifying
the Library or works based on it.

  10. Each time you redistribute the Library (or any work based on the
Library), the recipient automatically receives a license from the
original licensor to copy, distribute, link with or modify the Library
subject to these terms and conditions.  You may not impose any further
restrictions on the recipients' exercise of the rights granted herein.
You are not responsible for enforcing compliance by third parties with
this License.

  11. If, as a consequence of a court judgment or allegation of patent
infringement or for any other reason (not limited to patent issues),
conditions are imposed on you (whether by court order, agreement or
otherwise) that contradict the conditions of this License, they do not
excuse you from the conditions of this License.  If you cannot
distribute so as to satisfy simultaneously your obligations under this
License and any other pertinent obligations, then as a consequence you
may not distribute the Library at all.  For example, if a patent
license would not permit royalty-free redistribution of the Library by
all those who receive copies directly or indirectly through you, then
the only way you could satisfy both it and this License would be to
refrain entirely from distribution of the Library.

If any portion of this section is held invalid or unenforceable under any
particular circumstance, the balance of the section is intended to apply,
and the section as a whole is intended to apply in other circumstances.

It is not the purpose of this section to induce you to infringe any
patents or other property right claims or to contest validity of any
such claims; this section has the sole purpose of protecting the
integrity of the free software distribution system which is
implemented by public license practices.  Many people have made
generous contributions to the wide range of software distributed
through that system in reliance on consistent application of that
system; it is up to the author/donor to decide if he or she is willing
to distribute software through any other system and a licensee cannot
impose that choice.

This section is intended to make thoroughly clear what is believed to
be a consequence of the rest of this License.

  12. If the distribution and/or use of the Library is restricted in
certain countries either by patents or by copyrighted interfaces, the
original copyright holder who places the Library under this License may add
an explicit geographical distribution limitation excluding those countries,
so that distribution is permitted only in or among countries not thus
excluded.  In such case, this License incorporates the limitation as if
written in the body of this License.

  13. The Free Software Foundation may publish revised and/or new
versions of the Lesser General Public License from time to time.
Such new versions will be similar in spirit to the present version,
but may differ in detail to address new problems or concerns.

Each version is given a distinguishing version number.  If the Library
specifies a version number of this License which applies to it and
"any later version", you have the option of following the terms and
conditions either of that version or of any later version published by
the Free Software Foundation.  If the Library does not specify a
license version number, you may choose any version ever published by
the Free Software Foundation.

  14. If you wish to incorporate parts of the Library into other free
programs whose distribution conditions are incompatible with these,
write to the author to ask for permission.  For software which is
copyrighted by the Free Software Foundation, write to the Free
Software Foundation; we sometimes make exceptions for this.  Our
decision will be guided by the two goals of preserving the free status
of all derivatives of our free software and of promoting the sharing
and reuse of software generally.

                            NO WARRANTY

  15. BECAUSE THE LIBRARY IS LICENSED FREE OF CHARGE, THERE IS NO
WARRANTY FOR THE LIBRARY, TO THE EXTENT PERMITTED BY APPLICABLE LAW.
EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR
OTHER PARTIES PROVIDE THE LIBRARY "AS IS" WITHOUT WARRANTY OF ANY
KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE.  THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE
LIBRARY IS WITH YOU.  SHOULD THE LIBRARY PROVE DEFECTIVE, YOU ASSUME
THE COST OF ALL NECESSARY SERVICING, REPAIR OR CORRECTION.

  16. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN
WRITING WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY
AND/OR REDISTRIBUTE THE LIBRARY AS PERMITTED ABOVE, BE LIABLE TO YOU
FOR DAMAGES, INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR
CONSEQUENTIAL DAMAGES ARISING OUT OF THE USE OR INABILITY TO USE THE
LIBRARY (INCLUDING BUT NOT LIMITED TO LOSS OF DATA OR DATA BEING
RENDERED INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD PARTIES OR A
FAILURE OF THE LIBRARY TO OPERATE WITH ANY OTHER SOFTWARE), EVEN IF
SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH
DAMAGES.

                     END OF TERMS AND CONDITIONS

```

`README.txt`
```text

Source installation information for modders
-------------------------------------------
This code follows the Minecraft Forge installation methodology. It will apply
some small patches to the vanilla MCP source code, giving you and it access 
to some of the data and functions you need to build a successful mod.

Note also that the patches are built against "un-renamed" MCP source code (aka
SRG Names) - this means that you will not be able to read them directly against
normal code.

Setup Process:
==============================

Step 1: Open your command-line and browse to the folder where you extracted the zip file.

Step 2: You're left with a choice.
If you prefer to use Eclipse:
1. Run the following command: `./gradlew genEclipseRuns`
2. Open Eclipse, Import > Existing Gradle Project > Select Folder 
   or run `gradlew eclipse` to generate the project.

If you prefer to use IntelliJ:
1. Open IDEA, and import project.
2. Select your build.gradle file and have it import.
3. Run the following command: `./gradlew genIntellijRuns`
4. Refresh the Gradle Project in IDEA if required.

If at any point you are missing libraries in your IDE, or you've run into problems you can 
run `gradlew --refresh-dependencies` to refresh the local cache. `gradlew clean` to reset everything 
(this does not affect your code) and then start the process again.

Mapping Names:
=============================
By default, the MDK is configured to use the official mapping names from Mojang for methods and fields 
in the Minecraft codebase. These names are covered by a specific license. All modders should be aware of this
license, if you do not agree with it you can change your mapping names to other crowdsourced names in your 
build.gradle. For the latest license text, refer to the mapping file itself, or the reference copy here:
https://github.com/MinecraftForge/MCPConfig/blob/master/Mojang.md

Additional Resources: 
=========================
Community Documentation: https://docs.minecraftforge.net/en/1.20.1/gettingstarted/
LexManos' Install Video: https://youtu.be/8VEdtQLuLO0
Forge Forums: https://forums.minecraftforge.net/
Forge Discord: https://discord.minecraftforge.net/

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

`changelog.txt`
```text
1.20.1 Changelog
47.4
====
 - 47.4.13 Migrate to Github Actions
 - 47.4.12 Fix loading ImmediateWindowHandler services from the wrong Module Layer. Closes #10719
           Fix errors not logging to log files when early loading screen errors.
           Fix generics compile issue in ConctendatedListView.
 - 47.4.11 Allow loading ImmediateWindowProvider from mods dir (#10718)
 - 47.4.10 Work around mixins targeting IForgePlayer#canReach (#10680)
 - 47.4.9  Bump ASM to 9.8 for Java 25 runtime support  (#10664)
 - 47.4.8  Optimize fix for MC-176559 (#10649)
 - 47.4.7  Ignore item equality when checking shouldCauseBlockBreakReset, fixes #10645 (#10646)
 - 47.4.6  Allow command suggestions/autocomplete to search all modded paths at once without namespace provided (#10595)
 - 47.4.5  Fix ServerChatEvent thread not using correct class loader. (#10591)
 - 47.4.4  Fix ignitedByLava making blocks permanently flammable (#10570)
 - 47.4.3  Add back magic number to make mixin happy.
 - 47.4.2  Fix container screens closing when opened with an extended REACH attribute. Fixes #10560
           Also fixes cases where creative mode effected reach incorrectly.
 - 47.4.1  Deprecate EntityRenderersEvent.AddLayers functions that hard case to LivingEntityRenderers as the backing maps do not guarantee that type. (#10513)
           Co-authored-by: LexManos <LexManos@gmail.com>
 - 47.4.0  1.20.1 RB 4
           https://forums.minecraftforge.net/topic/154387-forge-474-minecraft-1201/

47.3
====
 - 47.3.39 Fix ForgeDev's test runs not working due to dead test mod (#10483)
 - 47.3.38 Cache this.useItem before running item break logic, Fixes #10344 (#10376)
 - 47.3.37 Speed up mod annotation scanning by ~30% (#10470)
           Co-authored-by: LexManos <LexManos@gmail.com>
 - 47.3.36 Add missed license headers (#10479)
 - 47.3.35 Add '#forge:chorus_additionally_grows_on' tag for similar mechanics to '#minecraft:azalea_grows_on' but for chorus (#10456)
 - 47.3.34 Fix cancelling ProjectileImpactEvent still firing onBlockHit (#10481)
 - 47.3.33 Honor attacker shield disabling status (#10321)
 - 47.3.32 Add fast graphics render type to block model jsons (#10393)
           Make modded leaves behave like vanilla leaves by default (Fixes #10389)
 - 47.3.31 Fix invalidly symlinked worlds crashing on level select (#10439)
 - 47.3.30 Backport even more future ResourceLocation methods (#10428)
 - 47.3.29 Ensure NetworkConstants is loaded before mod construction (#10407)
 - 47.3.28 Account for problematic mixins in VillagerTrades.EmeraldsForVillagerTypeItem (#10402)
 - 47.3.27 Fix incorrect method reference in TntBlock.explode()
 - 47.3.26 Fix issues in VillagerTrades.EmeraldsForVillagerTypeItem related to custom Villager Types (#10315)
           Add VillagerType#registerBiomeType
 - 47.3.25 Add `clientSideOnly` feature to mods.toml (#10085) (backport of #9804 to 1.20.1)
           Co-authored-by: Jonathing <me@jonathing.me>
 - 47.3.24 Fix non-passengers being tickable without checking canUpdate() (#10304)
 - 47.3.23 Fix finalizeSpawn's return value not being used correctly (#10301)
 - 47.3.22 Bump CoreMods to 5.2.4 (#10263)
 - 47.3.21 Allow mipmap lowering to be disabled (#10252)
 - 47.3.20 Add optional fix of use item duration, disabled by default (#10246)
 - 47.3.19 Backport some Vanilla 1.21 `ResourceLocation` methods (#10241)
           Co-authored-by: Paint_Ninja <PaintNinja@users.noreply.github.com>
 - 47.3.18 Simplify memory usage display on loading screen (#10233)
           Co-authored-by: Paint_Ninja <PaintNinja@users.noreply.github.com>
 - 47.3.17 Deprecate `@ObjectHolder`, add a couple of fast-paths (#10228)
           Co-authored-by: Paint_Ninja <PaintNinja@users.noreply.github.com>
 - 47.3.16 Skip Vanilla classes for the `CapabilityTokenSubclass` transformer (#10221)
           Co-authored-by: Paint_Ninja <PaintNinja@users.noreply.github.com>
 - 47.3.15 Skip Forge classes in the RuntimeEnumExtender transformer (#10216)
           Mod classes are still transformed as usual
           Co-authored-by: Paint_Ninja <PaintNinja@users.noreply.github.com>
 - 47.3.14 Skip processing Forge classes in `RuntimeDistCleaner` (#10208)
           Co-authored-by: Paint_Ninja <PaintNinja@users.noreply.github.com>
 - 47.3.13 Disable clean on TeamCity (#10258)
 - 47.3.12 Bump CoreMods to 5.2 (#10130)
           Full Changelog:
           https://gist.github.com/Jonathing/c3ad28b2a048ac839a7baba5417ee870
           The key features are:
           - ES6 language support
           - Thoroughly updated ASMAPI, with full documentation
           - Bug fixes (some optional for backwards-compatibility)
           - Partial internal code cleanup
           - Request CoreMods to not apply fix for ASMAPI.findFirstInstructionBefore by default
           - Updated ASM to 9.7.1
           - Updated Nashorn to 15.4
 - 47.3.11 Remove unneeded boat patch (backport of #10061 to 1.20.1) (#10096)
           Co-authored-by: andan42 <49289986+andan42@users.noreply.github.com>
 - 47.3.10 Optionally supply FMLJavaModLoadingContext as a param to mod constructors (backport of #10074 to 1.20.1) (#10100)
           Co-authored-by: RealMangoRage <andrew333awesome@outlook.com>
 - 47.3.9  Minor cleanup to ModListScreen and VersionChecker (backport of #9988 to 1.20.1) (#10095)
 - 47.3.8  Cleanup FML Bindings (backport of #10004 to 1.20.1) (#10094)
 - 47.3.7  Early display fixes/workarounds for buggy drivers. Backport of #9921 to 1.20.1 (#10073)
 - 47.3.6  Add a way to render tooltips from Formatted text and TooltipComponents elements (#10055)
           Backport of #10056 for 1.20.1
 - 47.3.5  Make HangingSignBlockEntity useable with custom BlockEntityTypes. #10038
 - 47.3.4  Unlock wrapped registries when firing register events. (#10035)
           Co-authored-by: LexManos <LexManos@gmail.com>
 - 47.3.3  Choose default JarJar mod file type based on parent JAR (#10023)
           Co-authored-by: thedarkcolour <30441001+thedarkcolour@users.noreply.github.com>
 - 47.3.2  Fixed falling block entities not rendering as moving blocks (#10006) (#10018)
           Co-authored-by: Ven <tudurap.com@gmail.com>
 - 47.3.1  Fix boat travel distance being incorrect. Closes #9997 #9999
 - 47.3.0  1.20.1 RB 3
           https://forums.minecraftforge.net/topic/139825-forge-473-minecraft-1201/

47.2
====
 - 47.2.36 Bump gradle to 8.8
 - 47.2.35 Bump gradle and java runtime version on team city
 - 47.2.34 Fix LevelSettings ignoring data configuration. Close #9938
 - 47.2.33 Fix erroneous patch in FireBlock. Closes #9996
 - 47.2.32 Fix early window crash when parsing options.txt (#9934)
           Backport of #9933
 - 47.2.31 Prevent mixins from crashing the game when there are missing mods (#9916)
           1.20.1 backport of 49.0.14
 - 47.2.30 Fix NPE when acceptableValues in defineInList() does not allow nulls, backport of #9903 (#9907)
           Co-authored-by: J-RAP <SrRapero720@hotmail.com>
 - 47.2.29 Fix Crowdin (#9929)
 - 47.2.28 Optimise capabilities a tad, backport of #9886 (#9911)
 - 47.2.27 Add helper method to `OnDatapackSyncEvent`, backport of #9901 (#9919)
 - 47.2.26 Add CPU usage config option to early window, hide by default, backport of #9866 (#9915)
 - 47.2.25 Fix slightly offset mods screen link positioning, backport of #9860 (#9861)
           Co-authored-by: Dennis C <11262040+XFactHD@users.noreply.github.com>
 - 47.2.24 Make common config screen registration tasks easier, backport of #9884 (#9912)
 - 47.2.23 Add Leaves method to ModelProvider.java (#9889)
 - 47.2.22 [1.20.1] Bump CoreMods and ASM (#9897)
           - CoreMods 5.1.2 -> 5.1.6
           - ASM 9.6 -> 9.7
 - 47.2.21 Bump some deps (#9880)
           - CoreMods 5.0.1 -> 5.1.2
           - ASM 9.5 -> 9.6
           - Installer 2.1 -> 2.2
           - Installer tools 1.3.0 -> 1.4.1
 - 47.2.20 Fix missing patch for Item.onInventoryTick. Closes #9812
 - 47.2.19 Make common DisplayTest registration tasks easier (#9823)
 - 47.2.18 Optimise ForgeConfigSpec and make Range public (#9824)
           Backport of #9810 to 1.20.1
 - 47.2.17 Datagen addOptionalTag/s methods that allow passing the TagKey itself instead of passing the location (#9807) (#9808)
 - 47.2.16 Update VersionSupportMatrix.java (#9805)
 - 47.2.15 Backport of Registries optimization, now uses fastutils collections to minimize boxing
 - 47.2.14 Fix patch offset
 - 47.2.13 Fix fire related mobs not taking enough freezing damage. Closes #9686
 - 47.2.12 Fix TagLoader error not printing tag name correctly. Closes #9693
 - 47.2.11 Fix LoadingErrorScreen inner headers are not centered. Closes #9687
 - 47.2.10 Rework KeyModifiers system to properly allow keybinds to be triggered when multiple modifiers are pressed.
           Fix setting keybinds whel using keyboard inputs to select the menu. Closes #9793
 - 47.2.9  Fix KeyModifiers not being properly taken into account. Closes #9806
 - 47.2.8  Don't turn off VSync when rendering from Minecraft context (#9801)
           Co-authored-by: embeddedt <42941056+embeddedt@users.noreply.github.com>
 - 47.2.7  Fix rare crash with early display window, fixes MinecraftForge#9673 (#9799)
 - 47.2.6  Fix tag loading being unordered. Closes #9774
 - 47.2.5  Fix misaligned patch in RegistryDataLoader
 - 47.2.4  Backport CrashReportAnalyser to 1.20.1 (#9757)
 - 47.2.3  Minor MDK changes (#9752)
 - 47.2.2  Improve mod description formatting in mods screen (#9769)
           Co-authored-by: Su5eD <su5ed@outlook.com>
 - 47.2.1  [1.20.1] Improve mod loading error message for errors inside mod constructors (#9707)
 - 47.2.0  1.20.1 RB

47.1
====
 - 47.1.47 Keep order of sources in PackRepository (#9702)
           Co-authored-by: dhyces <10985914+dhyces@users.noreply.github.com>
 - 47.1.46 Fix DelegatingPackResources searching resource path twice (#9697)
 - 47.1.45 Fix `Level` leak in debug HUD (#9699)
           Co-authored-by: malte0811 <malte0811@web.de>
 - 47.1.44 Fix PlayerSpawnPhantomsEvent not being fired (#9689)
 - 47.1.43 Enhance LivingBreathEvent and LivingDrownEvent. Closes #9680
           Also remove 3.5MB of useless data from the installer.
 - 47.1.42 Partially revert LazyOptional changes, now no longer internally uses weak references.
 - 47.1.41 Make LazyOptional's internal references to invalidation listeners use WeakReference, and allow modders to unregister themselves. Closes #8805
 - 47.1.40 Revert EntityEvent.Size changes to before #9018 was called. (#9679)
           Kept newly added methods for binary compatibility but deprecated them all for removal.
           The entire pose/eye/size system needs to be reevaluated and address some of Mojang's changes.
           However this should fix any bugs that pulling that PR may of caused.
 - 47.1.39 Add a config option to restore the calculate all normals behavior in case some setups require old broken behavior. (#9670)
 - 47.1.38 Fix rounding errors on models with 45 degree normals by favoring one Direction (#9669)
           Should fix flickering issues when breaking custom models and having our vanilla solution disabled.
 - 47.1.37 Moved ForgeHooksClient.onCreativeModeTabBuildContents to ForgeHooks to fix #9662
 - 47.1.36 Fix tag removal functionality that broke during the 1.19 update. Closes #9053 and #8949
 - 47.1.35 Replace string with forge tag in vanilla recipes. Closes #9062
 - 47.1.34 Fix new brain hooks not persisting active activities.
 - 47.1.33 Fix breaking overlay flickering on campfires by using vanilla method to calculate block normals. (#9664)
 - 47.1.32 Cleanup usages of static imports and build script so that our build doesn't spam useless error like messages.
           So that it is easier to see real errors.
           Add compatibility checking to standard testing tasks.
 - 47.1.31 Added Action value to PlayerInteractEvent.LeftClickEvent to expose what action fired the event. #9175
 - 47.1.30 Fix parameter names in IForgeDimensionSpecialEffects.adjustLightmapColors to better describe what they are. (#9656)
 - 47.1.29 Re-add EntityEvent.Size methods to maintain bincompat
 - 47.1.28 Added LivingMakeBrainEvent, to allow a consistent way for modders to manipulate entity Brains. #9292
 - 47.1.27 Add LivingSwapHandItemsEvent
 - 47.1.26 Fixed FluidUtil#tryFillContainer returning invalid result when simulating #9358
 - 47.1.25 Re-add in-game mod menu (#9652)
 - 47.1.24 Fix Entity eye height for multipart entities.
 - 47.1.23 Fix conflicting keybindings not having the correct click count set. #9360
 - 47.1.22 Fix the Emissive Rendering for Experimental Light Pipeline (#9651)
 - 47.1.21 Fixed AdvancementsScreen.java.patch buttons not rendering (#9649)
 - 47.1.20 Properly Handle Fluid Updates while in a Boat #9428
 - 47.1.19 New hook in IClientBlockExtensions to handle enabling tinting on breaking particles. #9446
 - 47.1.18 Fix invalid index when ticking itemstacks in a player nventory by adding a new onInventoryTick event. Closes #9453
 - 47.1.17 Make the FireworkShapes enum extensible (Closes #9486)
 - 47.1.16 Add `EmptyEnergyStorage` (#9487)
 - 47.1.15 Support IPv6 address compression for logged IPs
 - 47.1.14 Make item name rendering and status bar rendering respect additional gui overlays being rendered by mods (#9648)
 - 47.1.13 Fix EyeHeight event being fired twice (#9647)
 - 47.1.12 Add PlayerSpawnPhantomsEvent, utilized to block or forcefully allow PhantomSpawner to spawn phantoms (#9644)
 - 47.1.11 Fix creative mode screen not correctly using CreativeModeTab::getTabsImage (#9627)
 - 47.1.10 Add option to advertise dedicated servers to LAN.
 - 47.1.9  Fix entity eye height loop.
 - 47.1.8  Particle Description Data Provider.
 - 47.1.7  Add LivingBreatheEvent and LivingDrownEvent (#9525)
 - 47.1.6  Fix entity size event not being fired, changed it to split eye height and size calculations. (#9535)
 - 47.1.5  AlterGroundEvent for modifying block placement performed by AlterGroundDecorator (#9637)
 - 47.1.4  Change ProjectileHitEvent to return a result instead of being cancelable. Closes #9642
 - 47.1.3  Replace static import with regular one to fix S2S and non-official mappings. (#9633)
 - 47.1.2  Add missing null check for TagsProvider#existingFileHelper (#9638)
 - 47.1.1  Add GuiGraphics helpers for blitNineSliced and blitRepeating that support specifying a custom texture size (#9641)
 - 47.1.0  1.20.1 Recommended Build

47.0
====
 - 47.0.50 Fix FMLOnly loading. Closes #9609
 - 47.0.49 Improve logging for server connections (#9618)
 - 47.0.48 Fix placing fluids into waterlogged blocks with FluidUtil::tryPlaceFluid. To behave more like vanilla MC-127110 (#9586)
 - 47.0.47 Expose loaded RegistryAccess through AddReloadListenerEvent (#9613)
 - 47.0.46 Fix GLM applying to entities when killed. Closes #9551
 - 47.0.45 Add unordered creative tabs after vanilla and perform a second level sorting of tabs by registry name to ensure tabs are ordered the same between game restarts (#9612)
 - 47.0.44 Fix Early Loading window FPS snafu where it could spam (#9619)
           unlimited screen updates. Probably a good way to gently toast an ancient laptop.
 - 47.0.43 Make overloads consistent for defineListAllowEmpty in ForgeConfigSpec.Builder (#9604)
 - 47.0.42 Moved GameShuttingDownEvent hook to DedicatedServer class. Fixes #9601
 - 47.0.41 Fix PitcherCropBlock not calling canSustainPlant, not allowing it to be placed on custom farmland. Close #9611
 - 47.0.40 Add null check to NetworkHooks.openScreen. Closes #9597
 - 47.0.39 Fix ShieldBlockEvent not correctly performing damaged functions when not all damage is blocked. (#9615)
 - 47.0.38 Add IP address to client disconnect message. Closes #9603
 - 47.0.37 Fix hotbar items not dimming when sleeping in bed. Closes #9616
 - 47.0.36 Bump Eventbus to address NullPointerException when classloading things on some threads. Closes #9570
 - 47.0.35 Try and be a bit nicer about handling bad [feature] definitions  (#9606)
           * Try and be a bit nicer about handling bad [feature] definitions - they're single square bracket defined and require strings for feature bound values.
           * Some more tweaks to the feature system to output useful and well formatted error messages when bounds fail. Requires SPI 7.0.1 and the language string "fml.modloading.feature.missing" has changed.
           * Use immutable lists where possible and generally tidy things a bit
 - 47.0.34 [HotFix]: Somehow not caught by CI, but there was an issue in the SpawnUtils to handle.
 - 47.0.33 Remove amount from FluidStack hashCode to fix the equal/hashCode java contract (#9602)
 - 47.0.32 Add example usage for configs to the MDK (#9596)
           Demonstrates proper usage of different config value types, validation, transformation/parsing, load/reload handling and direct field access.
 - 47.0.31 Demonstrate configureEach in run configs (#9594)
 - 47.0.30 Reduce verbosity of prepareRuns doc in MDK (#9593)
 - 47.0.29 Lazily configure MDK tasks, improve IDE support (#9592)
 - 47.0.28 Fix not dropping xp for player sourced explosions and various other cases when xp should drop (#9588)
 - 47.0.27 add item handler capability to chiseled bookshelf (#9587)
 - 47.0.26 Fix ignoring maxUpStep method overrides on entities (#9583)
 - 47.0.25 Add missing damage type tag provider modid, existing file helper constructor overload (#9581)
 - 47.0.24 Expose holder lookup registry provider as a helper in RegistriesDatapackGenerator (#9580)
 - 47.0.23 Fix gametest collection causing secondary crash in loading error state (#9568)
 - 47.0.22 Fix SpriteCoordinateExpander not working with chained methods (MC-263524) (#9564)
 - 47.0.21 Expose EntityRendererProvider.Context to AddLayers event (#9562)
 - 47.0.20 [1.20] Add events for checking spawn conditions (SpawnPlacementCheck and PositionCheck) (#9469)
 - 47.0.19 Another tweak to the early display. We start a timer process while we create the window, in case it hangs. Also add a skip version config, to try and help in cases where the driver is stupid. (#9595)
 - 47.0.18 Auto generate names for modded loot pools. Fixes #9589 (#9591)
 - 47.0.17 More tweaks to the mod loading callbacks to make loading bars work better (#9585)
 - 47.0.16 Reimplement LootPool name patches and hooks (#9573)
 - 47.0.15 Fix experimental lighting pipeline breaking vanilla's emissive rendering. Closes #9552 (#9582)
 - 47.0.14 Update early loading default configs to match standard expectations. (#9577)
           Also allow a global override env variable for darkmode always. "FML_EARLY_WINDOW_DARK"
 - 47.0.13 Add proper duplicate mod error message that includes the mod id(s) and mod files. (#9474)
 - 47.0.12 Add missing stack tooltip rendering hooks (#9533)
           Fix automatic tooltip wrapping discarding empty lines used as spacers
 - 47.0.11 Add piston push reaction overrides to Block class (#9538)
 - 47.0.10 Fix missing calls to level-sensitive block SoundType getter (#9553)
 - 47.0.9  Fix forge registries that have wrappers/support tags not being in the HolderLookup Provider that is provided to the GatherDataEvent (#9566)
 - 47.0.8  Make IItemDecorator a functional interface again. Fixes #9563 (#9574)
 - 47.0.7  Make 1.20.x the main TC branch.
 - 47.0.6  Fix custom geometry in parent models not being resolved (#9572)
 - 47.0.5  Make the scheduled thread factory setDaemon on it's threads. Should allow things to close properly if something deadly happens early on. (#9575)
 - 47.0.4  This is an early display window system for forge. (#9558)
 - 47.0.3  fix the JIJ break by bumping SJH. apologies.
 - 47.0.2  update libs (#9565)
 - 47.0.1  Update Armor Layer Implementation to Match Vanilla (#9547)
 - 47.0.0  1.20.1 Update

46.0
====
 - 46.0.14 Fix JAR compatibility checks for 1.20 (#9556)
 - 46.0.13 [1.20] Add `AFTER_LEVEL` render level stage (#9555)
 - 46.0.12 Reorder overlay layers to match vanilla (#9550)
 - 46.0.11 Re-implement missing level-sensitive block light hook in ChunkAccess (#9536)
 - 46.0.10 Fix issues in the deserialization of empty ingredients (#9537)
 - 46.0.9  Fix wrong variable passed into EnchantmentClue lookup Fixes #9543 (#9544)
 - 46.0.8  Fix incorrect depth test state in debug graph rendering Fixes #9534 (#9539)
 - 46.0.7  Fix initCapabilities patch location in ServerLevel Fixes #9526 (#9531)
 - 46.0.6  Use Spinning Effect Intensity instead of Partial Tick for Portal Overlay Fixes #9529 (#9530)
 - 46.0.5  Fix getArmorModelHook patch, Fixex #9523 (#9528)
 - 46.0.4  Fix duplicate Map writes in PlayerList patch. (#9521)
 - 46.0.3  Fix Forge Version including MC version in MDK.
 - 46.0.2  Fix patch in light propagation (#9532)
 - 46.0.1  Attempt to fix jar signing
           Gradle 8 is stupid and doesn't (easily) allow in-place tasks, so a temporary fix has been made in ForgeGradle 6
 - 46.0.0  Forge 1.20
           - Creative mode tabs are now a registry; the `BuildContents` event was renamed to `BuildCreativeModeTabContentsEvent` and moved it to its own class
           - The pack format is now 15 for both resource packs and data packs
           - `ScreenUtils` was deprecated in favor of a `GuiGraphics` extension
           - Forge and the MDK were updated to Gradle 8 and FG6
           - The Forge common config file was removed (it only contained the deprecated old fields for resource caching, which was removed in 1.19.3)
           - Registry dummy entries were removed
           - `RemappingVertexPipeline` was fixed to forward the `endVertex()` call
           - Forge tool tags were removed in favor of vanilla ones
           Co-authored-by: ChampionAsh5357 <ash@ashwork.net>
           Co-authored-by: coehlrich <coehlrich@users.noreply.github.com>
           Co-authored-by: Dennis C <11262040+XFactHD@users.noreply.github.com>
           Co-authored-by: Matyrobbrt <matyrobbrt@gmail.com>

45.1
====
 - 45.1.0 1.19.4 Recommended Build

45.0
====
 - 45.0.66 Add method to GatherDataEvent to obtain collection of all input paths. (#9499)
 - 45.0.65 Log error when Sheets is class-loaded before registration is completed (#9475)
 - 45.0.64 [1.19.x] Re-implement RenderTooltipEvent.Color (#9497)
           * Reimplement RenderTooltipEvent.Color
           * Formatting, comments, EXC
           * Deprecate instead of replacing
 - 45.0.63 Add API for registering custom world preset editors (#9436)
 - 45.0.62 Remove unneeded extra reload of datapacks on world creation screen (#9454)
 - 45.0.61 Bump ASM to 9.5
 - 45.0.60 Fix crash when running server from root directory
           Fixes #9498
 - 45.0.59 Fix root transform matrix format, allow using all four root transform formats (#9496)
 - 45.0.58 Add missing AT lines to allow registering custom game rule boolean/integer types (#9489)
 - 45.0.57 [1.19.x] Fix SaplingGrowTreeEvent#setFeature being ignored in FungusBlock (#9485)
           Co-authored-by: Brennan Ward <3682588+Shadows-of-Fire@users.noreply.github.com>
 - 45.0.56 Restore AccessibilityOnboardingScreen
           Fixes #9488
 - 45.0.55 Update documentation on FinalizeSpawn (#9467)
 - 45.0.54 Fix fluids without sound event causing exception in tryFillContainer and tryEmptyContainer (#9445)
 - 45.0.53 Make FakePlayerFactory respect the given ServerLevel (#9479)
 - 45.0.52 Collect and log exceptions occurring in DeferredWorkQueue tasks (#9449)
 - 45.0.51 Fix `NamespacedWrapper#wrapAsHolder` (#9450)
 - 45.0.50 Fixes ChatScreen calling .setScreen (#9443)
           Fix test compile failures also.
 - 45.0.49 Determine the Forge version the PR was built against when running PR compat checks (#9374)
 - 45.0.48 Add buildscript test to error on deprecated members that should of been removed. (#9460)
 - 45.0.47 Remove erroneous brace patch in Inventory (#9462)
           Fixes #9459
 - 45.0.46 [1.19.4] Move root transform builder to ModelBuilder to allow use in ItemModelBuilder (#9456)
 - 45.0.45 Fix forge grindstone hooks allowing stacks of non-stackable items (#9457)
 - 45.0.44 [1.19.4] Fix FMLOnly (#9415)
 - 45.0.43 Fix ItemLayerModel erroneously adding particle texture to layer texture list (#9441)
 - 45.0.42 Temporary fix for Canceling ProjectileImpactEvents of Piercing ammo.
           Event needs to be re-worked to have finer control. #9370
 - 45.0.41 Fix dummy air blocks not being marked as air (#9440)
 - 45.0.40 Add support for splitting the login packet (#9367)
           It contains full copies of data registries and can easily surpass vanilla's limits
 - 45.0.39 Remove Attack Range and Reach Distance and add Block Reach and Entity Reach (#9361)
 - 45.0.38 Add default bucket sounds for milk (#9432)
 - 45.0.37 Deprecate Item.onUsingTick, as vanilla provides the same function in Item.onUseTick now. Closes #9342
 - 45.0.36 Fix ScreenEvent.Init.[Pre/Post] not working correctly (#9431)
 - 45.0.35 Allow FenceGateBlock to be used without a WoodType. Closes #9392
 - 45.0.34 Deprecate duplicate tool tags that vanilla added in 1.19.4
           We will maintain a seperate 'tools' tag until Mojang adds all relevent tool tags.
           Closes #9393
 - 45.0.33 Fix BlockEvent.Break not using ItemStack enchantment hooks.
 - 45.0.32 Move Block.onCatchFire to above block removal to allow usage of BlockEntity data. Closes #9400
 - 45.0.31 Fix FinalizeSpawn not blocking spawns during worldgen (#9420)
 - 45.0.30 Fixed issue with MutableHashedLinkedMap when removing multiple sequential entries in the middle of the map.
           Added Unit tests for MutableHashLinkedMap
           Added support for removing using the iterator
           Added concurrent modification detection to the iterator
           Added default constructor with basic hashing strategy.
           Closes #9426
 - 45.0.29 Loosen access for BucketItem's canBlockContainFluid (#9421)
 - 45.0.28 Update and Regenerate Datapacks (#9419)
           Add generation for pack.mcmeta
 - 45.0.27 Restore ability to change message in ClientChatEvent (#9377)
 - 45.0.26 Remove duplicate line in FoodData patch (#9424)
           The line was accidentally duplicated in the 1.19.4 update and patching
           process.
           Fixes #9422
 - 45.0.25 Rename RegisterParticleProviderEvent's register methods to describe what kind of particle providers they register (deprecating old methods to avoid breaking) and minor docs tweaks (#9388)
 - 45.0.24 Update pack versions (#9414)
 - 45.0.23 [1.19.4] Revamp and fix spawn events (#9133)
 - 45.0.22 [1.19.4] Replace blitOffset parameter with PoseStack in IItemDecorator (#9409)
           * Replace blitOffset with PoseStack in IItemDecorator
           * Circumvent breaking changes
           * Fix blitOffset type
 - 45.0.21 Fix JSON model root transforms (#9410)
 - 45.0.20 Fix tossed items not being able to be picked up by other players. Closes #9412 (#9404)
 - 45.0.19 Fix infinite BE render bounds failing frustum intersection test. Closes #9321 (#9407)
 - 45.0.18 Make ForgeSlider use the new vanilla texture (#9406)
 - 45.0.17 Add BlockSetType#register to accesstransformer.cfg (#9386)
 - 45.0.16 Add option to completely hide a crash-callable depending on a runtime value (#9372)
 - 45.0.15 Add isNewChunk to ChunkEvent.Load (#9369)
 - 45.0.14 Remove DistExecutor calls in PistonEventTest (#9348)
 - 45.0.13 Fix hardcoded precipitation in ClimateSettingsBuilder (#9402)
           This effectively caused all biomes to have precipitation, such as
           minecraft:desert.
           Fixes #9397
 - 45.0.12 Fix incorrect variable used for swimming check (#9403)
           Because of the incorrect variable, the check to stop sprinting (and stop
           swimming) never fired correctly.
           1.19.3's `flag5` variable was renamed to `flag7` in 1.19.4; however,
           this was not caught during patching because of the fuzzy patcher.
           Fixes #9399
 - 45.0.11 Fix incorrect boolean used for glint effect (#9401)
           The `flag1` variable is ultimately controlled by whether the armor slot
           being rendered is for the leggings, which explains this bug where the
           leggings always had the enchantment glint but not any other armor piece.
           Fixes #9394
 - 45.0.10 Fixed ModMismatchDisconnectedScreen displaying missing mods wrongly (#9398)
 - 45.0.9  Fix misaligned text render type patch (#9391)
 - 45.0.8  Remove thread filter from processing clientside custom payloads. Closes @9390
 - 45.0.7  Fix LivingEntity patch which caused crash while entities got hurt. Closes #9389
 - 45.0.6  Fix wrong parameters in `Screen#renderTooltipInternal` patch (#9387)
 - 45.0.5  Fix misaligned patch in LevelRenderer. Closes #9385
 - 45.0.4  Remove our fix for MC-121048 as it has been fixed by Vanilla (#9381)
 - 45.0.3  Fix advancements not loading, bug seems to be fixed by vanilla now. Closes #9384
 - 45.0.2  Fixed patch verifier for cases where patches lowered access levels. Closes #9383
 - 45.0.1  Fix crouching while sprinting stopping the player when step height is modified. Closes #9376
 - 45.0.0  Forge 1.19.4
           Properly move `ServerStatusPing` to codec
           Reimplement custom display contexts
           Co-authored-by: Matyrobbrt <matyrobbrt@gmail.com>
           Co-authored-by: coehlrich <coehlrich@users.noreply.github.com>

44.1
====
 - 44.1.23 Fix experimental world warning screen appearing everytime (#9375)
 - 44.1.22 Fix continuing to use items after dropping or when a shield breaks (MC-231097, MC-168573) (#9344)
 - 44.1.21 Add onStopUsing hook to IForgeItem (#9343)
 - 44.1.20 Document RegisterParticleProvidersEvent's APIs (#9346)
 - 44.1.19 Fix incorrect ListTag.getLongArray result (MC-260378) (#9351)
 - 44.1.18 Fix missing patch that left TagBuilder#replace unused (#9354)
 - 44.1.17 Add 2 new RenderLevelStageEvent.Stage for After Entities and After Block Entities (#9259)
 - 44.1.16 Cleanup StemBlock Patch (#9337)
 - 44.1.15 Cleanup ItemProperties patch (#9332)
 - 44.1.14 Make IForgeIntrinsicHolderTagAppender methods properly chainable (#9331)
 - 44.1.13 Fix in custom fluids not respecting max height correctly. (#9319)
 - 44.1.12 Fix inconsistent vaporization in BucketItem & FluidType (#9269)
 - 44.1.11 Fix reloading event firing during server shutdown and add explicit unloading event instead (#9016)
 - 44.1.10 Homogenize and/or holdersets when serializing to prevent serializing to NBT from crashing (#9048) Fixes #9043
 - 44.1.9  [1.19.x] Fix `ForgeSlider` not respecting custom height (#9237)
 - 44.1.8  Fix stepsound for blocks in the inside_step_sound_blocks tag. (#9318)
 - 44.1.7  Fix missing hanging sign material for modded wood type (#9303)
 - 44.1.6  Fire TickEvent.LevelTickEvent on ClientLevel tick (#9299)
 - 44.1.5  Add ClientChatReceivedEvent for system messages (#9284)
 - 44.1.4  PR Action update (#9274)
 - 44.1.3  fix HangingSignEditScreen crash when using custom wood types using modid (#9294)
 - 44.1.2  Bump SecureJarHandler version, to help identify invalid mods.
 - 44.1.1  [1.19.3] Hotfix missing null check in createUnbakedItemElements (#9285)
 - 44.1.0  Mark 1.19.3 Recommended Build

44.0
====
 - 44.0.49 [1.19.3] Allow Item and Elements models to specify static color, sky light, and block light values. (#9106)
 - 44.0.48 Fix StemBlock not checking canSustainPlant for the correct block, it now checks for Melons/Pumpkins instead of the stem itself. (#9270)
 - 44.0.47 Add github shared actions for automation purposes. (#9251)
 - 44.0.46 Add translate key for Forge pack.mcmeta description (#9260)
 - 44.0.45 Fix broken link for update checker docs in mdk (#9271)
 - 44.0.44 Remove duplicate updateNeighbourForOutputSignal call Fixes #9169 (#9234)
 - 44.0.43 Add helper methods to access the set of loaded sprite locations (#9223)
 - 44.0.42 Disable guiLight3d for generated item models (#9230)
 - 44.0.41 Remove resource caching (#9254)
 - 44.0.40 Add TradeWithVillagerEvent (#9244)
 - 44.0.39 Update link for Parchment "Getting Started" (#9243)
 - 44.0.38 Allows DatapackBuiltinEntriesProvider to datagen LevelStems (#9247)
 - 44.0.37 Add a method to LootContext.Builder that allows changing the queried loot table id (#9084)
 - 44.0.36 [1.19.3] Fix Datagen Tests and Providers (#9212)
 - 44.0.35 Fix concrete powder not being hydrated by singular water sources (#9236)
 - 44.0.34 [1.19.3] Fix LootTableLoadEvent not getting fired (#9239)
 - 44.0.33 Allow using custom factories in button builders (#9238)
 - 44.0.32 Fix logspam when a root resource is requested from DelegatingPackResources, fixes #9197 (#9227)
 - 44.0.31 [1.19.3] Fix `retrieveRegistryLookup` attempting to get the registry lookup from a `HolderGetter` (#9225)
 - 44.0.30 [1.19.3] Add ability to datagen forge specific values in pack.mcmeta (#9221)
           Co-authored-by: sciwhiz12 <sciwhiz12@gmail.com>
 - 44.0.29 Add block atlas config to register forge:white texture (#9187)
 - 44.0.28 Fix ExtendedButton not being highlighted when focused (#9144)
 - 44.0.27 Separate checkAndFix from the check* tasks. (#9213)
 - 44.0.26 Fix forge resources overriding vanilla ones (#9222)
 - 44.0.25 Fix tooltip customization not working for creative inventory (#9218)
 - 44.0.24 Fix glowing item frame entity's texture (#9126)
           Fixes #9123
 - 44.0.23 Fix datapack registries not being synced to clients (#9219)
 - 44.0.22 Fix creatives tabs rendering overlapping tabs if the selected tab isn't on the current page. (#9214)
 - 44.0.21 Fix `SidedInvWrapper` not accounting for vanilla stacking special cases in brewing stands and furnaces (#9189)
 - 44.0.20 Update to the latest JarJar. (#9217)
 - 44.0.19 Specify NetworkHooks#getEntitySpawningPacket Generic Return Type (#9220)
 - 44.0.18 Fix using a DeferredRegister on a non-forge wrapped registry. Closes #9199
 - 44.0.17 Add support for custom CreativeModeTab implementations (#9210)
 - 44.0.16 Simplify tree grower patches (#9209)
 - 44.0.15 Replace AdvancementProvider patch with Forge helper (#9188)
 - 44.0.14 Allow using `PackOutput`s in Forge-added datagen classes (#9182)
 - 44.0.13 Add simpleBlockWithItem for data gens (#9170)
 - 44.0.12 Fix running test mods (#9211)
 - 44.0.11 [1.19.3] Fix models nested in custom geometries not resolving parents (#9200)
 - 44.0.10 Fix OBJ Loader caches not being thread-safe. (#9204)
 - 44.0.9  [1.19.3] Add event before baked models are cached by the BlockModelShaper (#9190)
 - 44.0.8  Fix compatibility checker task configuration (#9202)
 - 44.0.7  Fix chat offset (#9184)
 - 44.0.6  Redesign CreativeTab collection event to be a lot more straight forward. (#9198)
 - 44.0.5  Move ICondition patch placement to before MC throws an error.
           Disable the explicitly erroring test biome modifier.
 - 44.0.4  Fix BlockStateProvider not waiting for models before finishing. (#9196) Fixes #9195:
 - 44.0.3  Fix tooltips not rendering on screens. Closes #9191
 - 44.0.2  Fix merged mod resource pack not returning all resources with the same name when asked. Closes #9194
 - 44.0.1  Fix searching using the wrong prefix for items or tags. Fixes #9176 Fixes #9179 (#9177)
 - 44.0.0  Forge 1.19.3
           Created a CreativeModeTabEvent to register creative mode tabs and populate entries per tab
           Moved datapack registries to DataPackRegistryEvent.NewRegistry event instead of tying them to ForgeRegistry
           Made it easier for mods to datagen datapack builtin entries with DatapackBuiltinEntriesProvider
           Provided access to lookupProvider for datagen
           Updated dependencies to match versions used by vanilla and update JarJar to 0.3.18
           Added a test mod for the new CreativeModeTabEvent
           Throws better error message for Forge registries in tag datagen
           Deleted ForgeRegistryTagsProvider
           Updated ClientChatReceivedEvent and ServerChatEvent for Mojang changes
           Added patches for both sign related methods in ModelLayers
           Changed RegisterShadersEvent to use ResourceProvider
           Migrated old Mojang math types to JOML
           Co-authored-by: Marc Hermans <marc.hermans@ldtteam.com>
           Co-authored-by: LexManos <LexManos@gmail.com>
           Co-authored-by: sciwhiz12 <arnoldnunag12@gmail.com>
           Co-authored-by: coehlrich <coehlrich@users.noreply.github.com>

43.2
====
 - 43.2.0 43.2 Recommended Build.

43.1
====
 - 43.1.65 Allow discovering services from the mods folder that use java's modular definition. (#9143)
 - 43.1.64 Make Datapack Registries support ICondition(s) (#9113)
 - 43.1.63 Enable additional build types to handle pull request validation. (#9159)
 - 43.1.62 Check source permission level before selector permission (#9147)
           In some situations, such as execution of a function by an advancement as
           part of its reward, a command source stack may have a backing source of
           a ServerPlayer which may lack the entity selector permission and have an
           explicit permission level that should allow the use of entity selectors,
           through CommandSourceStack#withPermission.
           We now check if the permission level of the command source stack is
           sufficient for entity selectors _before_ checking if the source is a
           player and if they have the requisite permission.
           This means that an operator permission level of 2 will always override
           the Forge entity selector permission.
           Fixes #9137
 - 43.1.61 Fix fires spreading too/igniting custom portal frames. (#9142)
 - 43.1.60 Add supplier to FlowerBlock so it works with custom MobEffects (#9139)
 - 43.1.59 Fix some logical bugs related to the Grindstone Event (#9089)
 - 43.1.58 Call baked model's `getModelData` before `getRenderTypes` (#9163)
 - 43.1.57 Make Util.memoize thread-safe (#9155)
 - 43.1.56 Rendering tweaks and fixes: Part 4 (#9065)
 - 43.1.55 Fix `Transformation` loading `PoseStack` (#9083)
 - 43.1.54 Add simple block appearance API (#9066)
 - 43.1.53 Fix invalidated modded packets when on LAN (#9157)
 - 43.1.52 Improve extensibility of DetectorRailBlock and PoweredRailBlock (#9130)
 - 43.1.51 Fix launch handler minecraft classpath locator (#9120)
 - 43.1.50 Add HitResult to `EntityTeleportEvent$EnderPearl` (#9135)
 - 43.1.49 Throw aggregate exception for erroneous registry event dispatch (#9111)
           This means that exceptions occurring during the dispatch of the registry
           events, such as those from the suppliers of RegistryObjects, properly
           cause a crash rather than merely being logged and allowing the game to
           reach the main menu.
           Fixes #8720
 - 43.1.48 Add missing semi-colon near the Dist import statement in example mod.
 - 43.1.47 Fix ClientModEvents example not subscribing to client-sided events (#9097)
 - 43.1.46 Use GitHub action to lock issues with the `spam` label (#9087)
 - 43.1.45 Remove structures slave map to Feature registry (#9091)
 - 43.1.44 Improve logging of missing or unsupported dependencies (#9104)
 - 43.1.43 [1.19.x] Fix ValueSpec caching the return value incorrectly (#9046)
 - 43.1.42 [1.19.x] Add event for registering spawn placements, and modifying existing (#9024)
 - 43.1.41 [1.19.x] Add event for items being stacked or swapped in a GUI. (#9050)
 - 43.1.40 [1.19.x] Fix PlayerInteractEvent.EntityInteractSpecific not cancelling on a server (#9079)
 - 43.1.39 Fix canceling phantom spawns preventing any further attempts that tick. (#9041)
 - 43.1.38 Rename fluid type milk translation keys (#9077)
 - 43.1.37 Fix minecart speed with water (#9076)
 - 43.1.36 Add a cancellable event that gets fired when a Totem of Undying is used (#9069)
 - 43.1.35 Fix performance issue and logging when resource caching is enabled (#9029)
 - 43.1.34 Fix NPE when feeding wolves and cats (#9074)
 - 43.1.33 Fix logically breaking change to ForgeConfigSpec.Builder#comment where modders could not add a empty line to the start of comments. (#9061)
 - 43.1.32 Fix ServiceLoader bug
 - 43.1.31 Fix ClientChatReceivedEvent for system messages
 - 43.1.30 Make ForgeConfigSpec$Builder.comment able to be called multiple times for the same entry. (#9056)
 - 43.1.29 Fix control modifier for mac with `KeyMapping`s  using Alt instead of Super (#9057)
 - 43.1.28 Fix is_desert tag not being applied correctly. (#9051)
 - 43.1.27 Fix mob griefing event for SmallFireballs not using owner entity. (#9038)
 - 43.1.26 Fix minecarts on rails not properly slowing down in water (#9033)
 - 43.1.25 Change codestyle for BookShelves tag. Closes #9027
           Add IS_CAVE tag Closes #8885
           Add IS_DESERT tag Closes #8979
           Simplify Mangrove Swamp tags Closes #8980
 - 43.1.24 Allow faces of an "elements" model to have disabled ambient occlusion (#9019)
 - 43.1.23 [1.19.x] Recipe ID-based grouping between modded and vanilla recipes. (#8876)
 - 43.1.22 Update fence_gates/wooden (#8936)
 - 43.1.21 [1.19.x] Added event for growing fungus (#8981)
 - 43.1.20 Added Bookshelves block tag (#8991)
 - 43.1.19 Create a Forge EntityType Tag for Bosses (#9017)
 - 43.1.18 Allow mods to specify shader import namespace (#9021)
 - 43.1.17 Grindstone Events (#8934)
           One to modify the output, and one to modify the input.
 - 43.1.16 Fix the serialized names of the enum (#9014)
 - 43.1.15 Fix `tryEmptyContainerAndStow` duping fluids with stackable containers (#9004)
 - 43.1.14 Add mod mismatch event (#8989)
 - 43.1.13 [1.19.x] add methods with more context to tree growers (#8956)
 - 43.1.12 [1.19.X] Adding more precise events for Advancements (#8360)
 - 43.1.11 Default IItemHandler capability for shulker box itemstacks (#8827)
           Co-authored-by: LexManos <LexManos@gmail.com>
 - 43.1.10 [1.19] Add hook for items to remain in the hotbar when picking blocks/entities (#8872)
 - 43.1.9  [1.19.x] Block Model Builder Root Transform Support (#8860)
           Co-authored-by: sciwhiz12 <sciwhiz12@gmail.com>
 - 43.1.8  [1.19.x] Make LivingSetAttackTargetEvent compatible with the Brain/Behavior system. (Port of PR #8918) (#8954)
 - 43.1.7  [1.19.x] Add IForgeBlock#onTreeGrow to replace IForgeBlock#onPlantGrow from 1.16 (#8999)
 - 43.1.6  [1.19.x] Moved Player.resetAttackStrengthTicker() to the end of Player.attack() (#9000)
 - 43.1.5  fix misplaced patch in sapling block (#9005)
 - 43.1.4  Fix failed entity interactions consuming the click. (#9007)
 - 43.1.3  Fix entity selector permission check to check original source (#8995)
           Permission checks should be against the command source and not the
           target entity, as is done in vanilla.
           Fixes #8994
 - 43.1.2  Hotfix for 1.19.2 item animation bug (#8987)
           * [HOT FIX]: Fixes #8985 by no-oping for vanilla models instead of throwing error
 - 43.1.1  Add ability to Auto register capabilities via annotation (#8972)
 - 43.1.0  1.19.2 RB

43.0
====
 - 43.0.22 Added ItemDecorator API (#8794)
 - 43.0.21 [1.19.x] Custom usage animations for items (#8932)
 - 43.0.20 Allow registering custom `ColorResolver`s (#8880)
 - 43.0.19 [1.19] Allow custom outline rendering on EntityRenderers and BlockEntityRenderers (#8938)
 - 43.0.18 Redirect checks for entity selector use to a permission (#8947)
           This allows greater flexibility for configuring servers with
           operator-like permissions to user groups through the permissions API and
           their permissions handler of choice without needing to grant the
           vanilla operator permission to any player.
           The new permission is "forge:use_entity_selectors", which is granted by
           default to players with permission level 2 (GAMEMASTERS) and above.
           The hook falls back to checking the permission level if the source of
           the command is not a ServerPlayer, such as for command blocks and
           functions.
 - 43.0.17 Allow FakePlayer to report its position (#8963)
 - 43.0.16 Add alternate version of renderEntityInInventory to allow for directly specifying the angles (#8961)
 - 43.0.15 Add cancellable ToastAddEvent (#8952)
 - 43.0.14 Modify ScreenEvent.RenderInventoryMobEffects to allow moving the effect stack left or right (#8951)
 - 43.0.13 Fix Enchantment#doPostHurt and Enchantment#doPostAttack being called twice for players. Fixes MC-248272 (#8948)
 - 43.0.12 Remove reflective implementation of ICustomPacket. (#8973)
           Make vanilla custom packets able to be sent multiple times. Closes #8969
 - 43.0.11 Filter name spaces to directories only. Closes #8413
 - 43.0.10 Fix a corner case where the UMLB can not extract a version from a library. (#8967)
 - 43.0.9  Fix worlds with removed dimension types unable to load. (#8959) Closes #8800
 - 43.0.8  Fix issue where unknown chunk generators would cause DFU to fail. (#8957)
 - 43.0.7  Fix comments and documentation that were missed during the review of #8712 (#8945)
 - 43.0.6  Make AnvilUpdateEvent fire even if the second input is empty, which means it fires even if only changing the item name. (#8905)
 - 43.0.5  Fix `LivingEntity#isBlocking` to use `ToolActions#SHIELD_BLOCK` instead of `UseAnim#BLOCK` (#8933)
 - 43.0.4  Add Custom HolderSet Types allowing for logical combining of sets. (#8928)
 - 43.0.3  Add values to VersionSupportMatrix to support loading mods that restrict versions to 1.19.1 on 1.19.2 (#8946)
 - 43.0.2  Fix certain particles not updating their bounding box when their position changes (#8925)
 - 43.0.1  Update EventBus to address concurrency issue in ModLauncher Factory. Closes #8924
 - 43.0.0  1.19.2

42.0
====
 - 42.0.9 Remove calls to getStepHeight in Player#maybeBackOffFromEdge (#8927)
 - 42.0.8 Add forge tags for tools and armors, these DO NOT replace ToolActions, and are designed just for Recipes. (#8914)
 - 42.0.7 Add Biomes.BEACH to Tags (#8892)
 - 42.0.6 Let NetworkInstance.isRemotePresent check minecraft:register for channel IDs.  (#8921)
 - 42.0.5 Add an event for when the chunk ticket level is updated (#8909)
 - 42.0.4 Re-add PotentialSpawns event (#8712)
 - 42.0.3 Fix misplaced patch in ItemEntityRenderer breaking ItemEntityRenderer#shouldBob() (#8919)
 - 42.0.2 [1.19] [HotFix] Fix the dedicated server not having access to the JiJ filesystems. (#8931)
 - 42.0.1 Match Mojang's action bar fix for MC-72687 (#8917)
 - 42.0.0 Forge 1.19.1
          Load natives from classpath
          Make command argument types a forge registry
          Add `EntityMobGriefingEvent` to `Allay#wantsToPickUp`
          Overhaul `ServerChatEvent` to use `ChatDecorator` system
          Remove `ClientChatEvent#setMessage` for now
          Gradle 7.5

41.1
====
 - 41.1.0 Mark 1.19 RB

41.0
====
 - 41.0.113 Allow faces of an "elements" model to be made emissive (#8890)
 - 41.0.112 Fix invalid channel names sent from the server causing the network thread to error. (#8902)
 - 41.0.111 Fix PlayerEvent.BreakSpeed using magic block position to signify invalid position. Closes #8906
 - 41.0.110 Fix cases where URIs would not work properly with JarInJar (#8900)
 - 41.0.109 Add new hook to allow modification of lightmap via Dimension special effects (#8863)
 - 41.0.108 Fix Forge's packet handling on play messages. (#8875)
 - 41.0.107 Add API for tab list header/footer (#8803)
 - 41.0.106 Allow modded blocks overriding canStickTo prevent sticking to vanilla blocks/other modded blocks (#8837)
 - 41.0.105 Multiple tweaks and fixes to the recent changes in the client refactor PR: Part 3 (#8864)
            Fix weighted baked models not respecting children render types
            Allow fluid container model to use base texture as particle
            Fix inverted behavior in composite model building. Fixes #8871
 - 41.0.104 Fix crossbows not firing ArrowLooseEvent (#8887)
 - 41.0.103 Add User-Agent header to requests made by the update checker (#8881)
            Format: Java-http-client/<Java version> MinecraftForge/<ForgeVer> <ModId>/<ModVersion>
 - 41.0.102 Output the full path in a crash report so it is easier to find the outer mod when a crash in Jar-In-Jar occurs. (#8856)
 - 41.0.101 Clean up the pick item ("middle mouse click") patches (#8870)
 - 41.0.100 [1.19.x] Hotfix for test mods while the refactor is ongoing
 - 41.0.99  add event to SugarCaneBlock (#8877)
 - 41.0.98  Fix Global Loot Modifiers not using Dispatch Codec (#8859)
 - 41.0.97  Allow block render types to be set in datagen (#8852)
 - 41.0.96  Fix renderBreakingTexture not using the target's model data (#8849)
 - 41.0.95  Multiple tweaks and fixes to the recent changes in the client refactor PR: Part 2 (#8854)
            * Add getter for the component names in an unbaked geometry
            * Fix render type hint not being copied in BlockGeometryBakingContext
            * Ensure BlockRenderDispatches's renderSingleBlock uses the correct buffer
 - 41.0.94  [1.19.x] Apply general renames, A SRG is provided for modders. (#8840)
            See https://gist.github.com/SizableShrimp/882a671ff74256d150776da08c89ef72
 - 41.0.93  Fix mob block breaking AI not working correctly when chunk 0,0 is unloaded. Closes #8853
 - 41.0.92  Fix crash when breaking blocks with multipart models and remove caching. Closes #8850
 - 41.0.91  Fixed `CompositeModel.Baked.Builder.build()` passing arguments in the wrong order (#8846)
 - 41.0.90  Make cutout mipmaps explicitly opt-in for item/entity rendering (#8845)
            * Make cutout mipmaps explicitly opt-in for item/entity rendering
            * Default render type domain to "minecraft" in model datagens
 - 41.0.89  Fixed multipart block models not using the new model driven render type system. (#8844)
 - 41.0.88  Update to the latest JarJar to fix a collision issue where multiple jars could provide an exact match. (#8847)
 - 41.0.87  Add FML config to disable DFU optimizations client-side. (#8842)
            * Add client-side command line argument to disable DFU optimizations.
            * Switch to using FMLConfig value instead.
 - 41.0.86  [1.19] Fixed broken BufferBuilder.putBulkData(ByteBuffer) added by Forge (#8819)
            * Fixes BufferBuilder.putBulkData(ByteBuffer)
            * use nextElementByte
            * Fixed merge conflict
 - 41.0.85  [1.19.x] Fix shulker boxes allowing input of items, that return false for Item#canFitInsideContainerItems, through hoppers. (#8823)
            * Make ShulkerBoxBlockEntity#canPlaceItemThroughFace delegate to Item#canFitInsideContainerItems.
            * Switch to using Or and add comment.
            * Switch Or to And.
 - 41.0.84  [1.19.x] Added RenderLevelStageEvent to replace RenderLevelLastEvent (#8820)
            * Ported RenderLevelStageEvent from 1.18.2
            * Updated to fix merge conflicts
 - 41.0.83  [1.19.x] Fix door datagenerator (#8821)
            * Fix door datagenerator
            Fix datagenerator for door blocks. Successor to #8687, addresses comments made there about statement complexity.
            * Fix extra space around parameter
            Fix extra space before comma around a parameter.
 - 41.0.82  Create PieceBeardifierModifier to re-enable piecewise beardifier definitions (#8798)
 - 41.0.81  Allow blocks to provide a dynamic MaterialColor for display on maps (#8812)
 - 41.0.80  [1.19.x] BiomeTags Fixes/Improvements (#8711)
            * dimension specific tag fix
            * remove forge:is_beach cause vanilla has it already
            * remove forge tags for new 1.19 vanilla tags (savanna, beach, overworld, end)
            Co-authored-by: Flemmli97 <Flemmli97@users.noreply.github.com>
 - 41.0.79  1.19 - Remove GlobalLootModifierSerializer and move to Codecs (#8721)
            * convert GLM serializer class to codec
            * cleanup
            * GLM list needs to be sorted
            * datagen
            * simplify serialization
            * fix test mods (oops)
            * properly use suppliers for codec as they are registry obj
 - 41.0.78  Implement item hooks for potions and enchantments (#8718)
            * Implement item hooks for potions and enchantments
            * code style fixes
 - 41.0.77  Re-apply missing patch to ServerLevel.EntityCallbacks#onTrackingEnd() (#8828)
 - 41.0.76  Double Bar Rendering fixed (#8806) (#8807)
            * Double Bar Rendering fixed (#8806)
            * Added requested changes by sciwhiz12
 - 41.0.75  Multiple tweaks and fixes to the recent changes in the client refactor PR (#8836)
            * Add an easy way to get the NamedGuiOverlay from a vanilla overlay
            * Fix static member ordering crash in UnitTextureAtlasSprite
            * Allow boss bar rendering to be cancelled
            * Make fluid container datagen use the new name
 - 41.0.74  Add FogMode to ViewportEvent.RenderFog (#8825)
 - 41.0.73  Provide additional context to the getFieldOfView event (#8830)
 - 41.0.72  Pass renderType to IForgeBakedModel.useAmbientOcclusion (#8834)
 - 41.0.71  Load custom ITransformationServices from the classpath in dev (#8818)
            * Add a classpath transformer discoverer to load custom transformation services from the classpath
            * Update ClasspathTransformerDiscoverer to 1.18
            * Update license year
            * Update license header
            * Fix the other license headers
            * Update ClasspathTransformerDiscoverer to 1.19
 - 41.0.70  Handle modded packets on the network thread (#8703)
            * Handle modded packets on the network thread
             - On the server we simply need to remove the call to
               ensureRunningOnSameThread.
             - On the client side, we now handle the packet at the very start of the
               call. We make sure we're running from a network thread to prevent
               calling the handling code twice.
               While this does mean we no longer call .release(), in practice this
               doesn't cause any leaks as ClientboundCustomPayloadPacket releases
               for us.
            * Clarify behaviour a little in the documentation
            * Javadoc formatting
            * Add a helper method for handling packets on the main thread
            Also rename the network thread one. Should make it clearer the expected
            behaviour of the two, and make it clearer there's a potentially breaking
            change.
            * Add back consumer() methods
            Also document EventNetworkChannel, to clarify the thread behaviour
            there.
            * Add since = "1.19" to deprecated annotations
 - 41.0.69  Cache resource listing calls in resource packs (#8829)
            * Make the resource lookups cached.
            * Include configurability and handle patch cleanup.
            * Document and comment the cache manager.
            * Make thread selection configurable.
            * Implement a configurable loading mechanic that falls back to default behaviour when the config is not bound yet.
            * Use boolean supplier and fix wildcard import.
            * Clean up the VPR since this is more elegant.
            * Clean up the VPR since this is more elegant.
            * Address review comments.
            * Address more review comments.
            * Fix formatting on `getSource`
            * Address comments by ichtt
            * Adapt to pups requests.
            * Stupid idea.
            * Attempt this again with a copy on write list.
            * Fix a concurrency and loading issue.
            * Fix #8813
            Checks if the paths are valid resource paths.
            * Move the new methods on vanilla Patch.
 - 41.0.68  Update SJH and JIJ
 - 41.0.67  Fix #8833 (#8835)
 - 41.0.66  Fix backwards fabulous check in SimpleBakedModel (#8832)
            Yet another blunder we missed during the review of #8786.
 - 41.0.65  Make texture atlas in StandaloneGeometryBakingContext configurable (#8831)
 - 41.0.64  [1.19.X] Client code cleanup, updates, and other refactors (#8786)
            * Revert "Allow safely registering RenderType predicates at any time (#8685)"
            This reverts commit be7275443fd939db9c58bcad47079c3767789ac1.
            * Renderable API refactors
            - Rename "render values" to "context"
            - Rename SimpleRenderable to CompositeRenderable to better reflect its use
            - Remove IMultipartRenderValues since it doesn't have any real use
            - Add extensive customization options to BakedModelRenderable
            * ClientRegistry and MinecraftForgeClient refactors
            - Add sprite loader manager and registration event
            - Add spectator shader manager and registration event
            - Add client tooltip factory manager and registration event
            - Add recipe book manager and registration event
            - Add key mapping registration event
            - Remove ClientRegistry, as everything has been moved out of it
            - Remove registration methods from MinecraftForgeClient, as they have dedicated events now
            * Dimension special effects refactors
            - Fold handlers into an extension class and remove public mutable fields
            - Add dimension special effects manager and registration event
            * HUD overlay refactors
            - Rename to IGuiOverlay match vanilla (instead of Ingame)
            - Add overlay manager and registration event
            - Move vanilla overlays to a standalone enum
            * Model loader refactors
            - Rename IModelLoader to IGeometryLoader
            - Add loader manager and registration event
            - Fold all model events into one
            - Move registration of additionally loaded models to an event
            - Remove ForgeModelBakery and related classes as they served no purpose anymore
            * Render properties refactors
            - Rename all render properties to client extensions and relocate accordingly
            - Move lookups to the respective interfaces
            * Model data refactors
            - Convert model data to a final class backed by an immutable map and document mutability requirements. This addresses several thread-safety issues in the current implementation which could result in race conditions
            - Transfer ownership of the data manager to the client level. This addresses several issues that arise when multiple levels are used at once
            * GUI and widget refactors
            - Move all widgets to the correct package
            - Rename GuiUtils and children to match vanilla naming
            * New vertex pipeline API
            - Move to vanilla's VertexConsumer
            - Roll back recent PR making VertexConsumer format-aware. This is the opposite of what vanilla does, and should not be relevant with the updated lighting pipeline
            * Lighting pipeline refactors
            - Move to dedicated lighting package
            - Separate flat and smooth lighters
            - Convert from a vertex pipeline transformer to a pure vertex source (input is baked quads)
            * Model geometry API refactors
            - Rename IModelGeometry to IUnbakedGeometry
            - Rename IModelConfiguration to IGeometryBakingContext
            - Rename other elements to match vanilla naming
            - Remove current changes to ModelState, as they do not belong there. Transforms should be specified through vanilla's system. ModelState is intended to transfer state from the blockstate JSON
            - Remove multipart geometries and geometry parts. After some discussion, these should not be exposed. Instead, geometries should be baked with only the necessary parts enabled
            * Make render types a first-class citizen in baked models
            - Add named render types (block + entity + fabulous entity)
            - Add named render type manager + registration event
            - Make BakedModel aware of render types and transfer control over which ones are used to it instead of ItemBlockRenderTypes (fallback)
            - (additional) Add concatenated list view. A wrapper for multiple lists that iterates through them in order without the cost of merging them. Useful for merging lists of baked quads
            * General event refactors
            - Several renames to either match vanilla or improve clarity
            - Relocate client chat event dispatching out of common code
            * Forge model type refactors
            - Rename SeparatePerspectiveModel to SeparateTransformsModel
            - Rename ItemModelMesherForge to ForgeItemModelShaper
            - Rename DynamicBucketModel to DynamicFluidContainerModel
            - Prefix all OBJ-related classes with "Obj" and decouple parsing from construction
            - Extract ElementsModel from model loader registry
            - Add EmptyModel (baked, unbaked and loader)
            - Refactor CompositeModel to take over ItemMultiLayerBakedModel
            - Remove FluidModel as it's not used and isn't compatible with the new fluid rendering in modern versions
            - Move model loader registration to a proper event handler
            - Update names of several JSON fields (backwards-compatible)
            - Update datagens to match
            * Miscellaneous changes and overlapping patches
            - Dispatch all new registration events
            - Convert ExtendedServerListData to a record
            - Add/remove hooks from ForgeHooksClient as necessary
            * Update test mods
            * Fix VertexConsumerWrapper returning parent instead of itself
            * Additional event cleanup pass
            As discussed on Discord:
            - Remove "@hidden" and "@see <callsite>" javadoc annotations from all client events and replace them with @ApiStatus.Internal annotation
            - Make all events that shouldn't be fired directly into abstract classes with protected constructors
            - Another styling pass, just in case (caught some missed classes)
            * Add proper deprecation javadocs and de-dupe some vertex consumer code
            * Replace sets of chunk render types with a faster BitSet-backed collection
            This largely addresses potential performance concerns that using a plain HashSet might involve by making lookups and iteration as linear as they can likely be (aside from using a plain byte/int/long for bit storage). Further performance concerns related to the implementation may be addressed separately, as all the implementation details are hidden from the end user
            * Requested changes
            - Remove MinecraftForgeClient and move members to Minecraft, IForgeMinecraft and StencilManager
            - Allow non-default elements to be passed into VertexConsumer and add support to derived classes
            - Move array instantiation out of quad processing in lighting pipeline
            - Fix flipped fluid container model
            - Set default UV1 to the correct values in the remapping pipeline
            - Minor documentation changes
            * Add/update EXC entries and fix AT comment
            * Add test mod as per Orion's request
            * Additional requested changes
            * Allow custom model types to request the particle texture to be loaded
            * Even more requested changes
            * Improve generics in ConcatenatedListView and add missing fallbacks
            * Fix fluid render types being bound to the fluid and not its holder
            * Remove non-contractual nullability in ChunkRenderTypeSet and add isEmpty
            Additionally, introduce chunk render type checks in ItemBlockRenderTypes
            Co-authored-by: Dennis C <xfacthd@gmx.de>
 - 41.0.63  Implement full support for IPv6 (#8742)
 - 41.0.62  Fix certain user-configured options being overwritten incorrectly due to validators. (#8780)
 - 41.0.61  Allow safely registering RenderType predicates at any time (#8685)
 - 41.0.60  Fix crash after loading error due to fluid texture gathering and config lookup (#8802)
 - 41.0.59  Remove the configuration option for handling empty tags in ingredients. (#8799)
            Now empty tags are considered broken in all states.
 - 41.0.58  Fix MC-105317 Structure blocks do not rotate entities correctly when loading (#8792)
 - 41.0.57  Fire ChunkWatchEvents after sending packets (#8747)
 - 41.0.56  Add item handler capability to chest boats (#8787)
 - 41.0.55  Add getter for correct BiomeSpecialEffectsBuilder to BiomeInfo$Builder (#8781)
 - 41.0.54  Fix BlockToolModificationEvent missing cancelable annotation (#8778)
 - 41.0.53  Fix ticking chunk tickets from forge's chunk manager not causing chunks to fully tick (#8775)
 - 41.0.52  Fix default audio device config loading string comparison issue (#8767)
 - 41.0.51  Fix missed vanilla method overrides in ForgeRegistry (#8766)
 - 41.0.50  Add MinecraftServer reference to ServerTickEvent (#8765)
 - 41.0.49  Fix TagsProviders for datapack registries not recognizing existing files (#8761)
 - 41.0.48  Add callback after a BlockState was changed and the neighbors were updated (#8686)
 - 41.0.47  Add biome tag entries for 1.19 biomes (#8684)
 - 41.0.46  Make fishing rods use tool actions for relevant logic (#8681)
 - 41.0.45  Update BootstrapLauncher to 1.1.1 and remove the forced
            merge of text2speech since new BSL does it.
 - 41.0.44  Merge text2speech libs together so the natives are part of the jar
 - 41.0.43  Make Forge ConfigValues implement Supplier. (#8776)
 - 41.0.42  Fix merge derp in AbstractModProvider and logic derp in ModDiscoverer
 - 41.0.41  Add "send to mods in order" method to ModList and use it (#8759)
            * Add "send to mods in order" method to ModList and use it in RegistryEvents and DataGen..
            * Also preserve order in runAll
            * Do better comparator thanks @pupnewfster
            * postEvent as well.
 - 41.0.40  Update SJH to 2.0.2.. (#8774)
            * Update SJH to 2.0.3..
 - 41.0.39  Sanity check the version specified in the mod file (#8749)
            * Sanity check the version specified in the mod file to
            make sure it's compatible with JPMS standards for
            version strings.
            Closes #8748
            Requires SPI 6
 - 41.0.38  Fix SP-Devtime world loading crash due to missing server configs (#8757)
 - 41.0.37  Remove ForgeWorldPreset and related code (#8756)
            Vanilla has a working replacement.
 - 41.0.36  Change ConfigValue#get() to throw if called before config loaded  (#8236)
            This prevents silent issues where a mod gets the value of the setting
            before configs are loaded, which means the default value is always
            returned.
            As there may be situations where the getting the config setting before
            configs are loaded is needed, and it is not preferable to hardcode the
            default value, the original behavior is made available through #getRaw.
            Implements and closes #7716
            * Remove getRaw() method
            This is effectively replaced with the expression `spec.isLoaded() ?
            configValue.get() : configValue.getDefault()`.
            * Remove forceSystemNanoTime config setting
            As implemented, it never had any effect as any place where the config
            value would be queried happens before the configs are loaded.
 - 41.0.35  Fix EnumArgument to use enum names for suggestions (#8728)
            Previously, the suggestions used the string representation of the enum
            through Enum#toString, which can differ from the name of the enum as
            required by Enum#valueOf, causing invalid suggestions (both in gui and
            through the error message).
 - 41.0.34  Jar-In-Jar (#8715)
 - 41.0.33  [1.19] Fix data-gen output path of custom data-pack registries (#8724)
 - 41.0.32  Fix player dive and surface animations in custom fluids (#8738)
 - 41.0.31  [1.19.x] Affect ItemEntity Motion in Custom Fluids (#8737)
 - 41.0.30  [1.19] Add support for items to add enchantments without setting them in NBT (#8719)
 - 41.0.29  [1.19.x] Add stock biome modifier types for adding features and spawns (#8697)
 - 41.0.28  [1.19.x] Fluid API Overhaul (#8695)
 - 41.0.27  Replace StructureSpawnListGatherEvent with StructureModifiers (#8717)
 - 41.0.26  Use stack sensitive translation key by default for FluidAttributes. (#8707)
 - 41.0.25  Delete LootItemRandomChanceCondition which added looting bonus enchantment incorrectly. (#8733)
 - 41.0.24  Update EventBus to 6.0, ModLauncher to 10.0.1 and BootstrapLauncher to 1.1 (#8725)
 - 41.0.23  Replace support bot with support action (#8700)
 - 41.0.22  Fix Reach Distance / Attack Range being clamped at 6.0 (#8699)
 - 41.0.21  [1.19.x] Fix mods' worldgen data not being loaded when creating new singleplayer worlds (#8693)
 - 41.0.20  [1.19.x] Fix experimental confirmation screen (#8727)
 - 41.0.19  Move is_mountain to forge's tag instead of vanilla's (#8726)
 - 41.0.18  [1.19.x] Add CommandBuildContext to Register Command Events (#8716)
 - 41.0.17  Only rewrite datagen cache when needed (#8709)
 - 41.0.16  Implement a simple feature system for Forge (#8670)
            * Implement a simple feature system for Forge. Allows mods to demand certain features are available in the loading system. An example for java_version is provided, but not expected to be used widely. This is more targeted to properties of the display, such as GL version and glsl profile.
            Requires https://github.com/MinecraftForge/ForgeSPI/pull/13 to be merged first in ForgeSPI, and the SPI to be updated appropriately in build.gradle files.
            * rebase onto 1.19 and add in SPI update
 - 41.0.15  displayTest option in mods.toml (#8656)
            * displayTest option in mods.toml
            * "MATCH_VERSION" (or none) is existing match version string behaviour
            * "IGNORE_SERVER_VERSION" accepts anything and sends special SERVERONLY string
            * "IGNORE_ALL_VERSION" accepts anything and sends an empty string
            * "NONE" allows the mod to supply their own displaytest using the IExtensionPoint mechanism.
            * Update display test with feedback and added the mods.toml discussion in mdk.
 - 41.0.14  Update forgeSPI to v5 (#8696)
 - 41.0.13  Make IVertexConsumers such as the lighting pipeline, be aware of which format they are dealing with. (#8692)
            Also fix Lighting pipeline ignoring the overlay coords from the block renderer.
 - 41.0.12  Fixed misaligned patch to invalidateCaps in Entity (#8705)
 - 41.0.11  Fix readAdditionalLevelSaveData (#8704)
 - 41.0.10  Fixes setPos to syncPacketPositionCodec (#8702)
 - 41.0.9   Fix wrong param passed to PlayLevelSoundEvent.AtEntity (#8688)
 - 41.0.8   Override initialize in SlotItemHandler, so it uses the itemhandler instead of container (#8679)
 - 41.0.7   Update MDK for 1.19 changes (#8675)
 - 41.0.6   Add helper to RecipeType, and fix eclipse compiler error in test class.
 - 41.0.5   Update modlauncher to latest (#8691)
 - 41.0.4   Fix getting entity data serializer id crashing due to improper port to new registry system (#8678)
 - 41.0.3   Fire registry events in the order vanilla registers to registries (#8677)
            Custom registries are still fired in alphabetical order, after all vanilla registries.
            Move forge's data_serializers registry to forge namespace.
 - 41.0.2   Add method with pre/post wrap to allow setting/clearing mod context. (#8682)
            Fixes ActiveContainer in ModContext not being present in registry events. Closes #8680
 - 41.0.1   Fix the Curlie oopsie
 - 41.0.0   Forge 1.19
            * Bump pack.mcmeta formats
            * 1.19 biome modifiers
            * Mark ClientPlayerNetworkEvent.LoggedOutEvent's getters as nullable
            * Add docs and package-info to client extension interfaces package
            * Move RenderBlockOverlayEvent hooks to ForgeHooksClient
            * Add package-infos to client events package
            * Rename SoundLoadEvent to SoundEngineLoadEvent
            This reduces confusion from consumers which may think the
            name SoundLoadEvent refers to an individual sound being loaded rather
            than the sound engine.
            * Document and change SoundLoadEvent to fire on mod bus
            Previously, it fired on both the mod bus and the Forge bus, which is
            confusing for consumers.
            * Delete SoundSetupEvent
            Looking at its original implementation shows that there isn't an
            appropriate place in the new sound code to reinsert the event, and the
            place of 'sound engine/manager initialization event' is taken already by SoundLoadEvent.
            * Perform some cleanup on client events
             - Removed nullable annotations from ClientPlayerNetworkEvent
             - Renamed #getPartialTicks methods to #getPartialTick, to be consistent
              with vanilla's naming of the partial tick
             - Cleanup documentation to remove line breaks, use the
              spelling 'cancelled' over
              'canceled', and improve docs on existing and
               new methods.
            * Remove EntityEvent.CanUpdate
            Closes MinecraftForge/MinecraftForge#6394
            * Switch to Jetbrains nullability annotations
            * New PlayLevelSoundEvent; replaces old PlaySoundAtEntityEvent
            * Remove ForgeWorldPresetScreens
            * Remove IForgeRegistryEntry
            * Remove use of List<Throwable> in FML's CompletableFutures
            * Add docs to mod loading stages, stages, and phases
            * Gradle 7.4.2
            * Use SLF4J in FMLLoader and other subprojects
            * Switch dynamic versions in subprojects to pinned ones
            * Switch ForgeRoot and MDK to FG plugin markers
            * Configure Forge javadoc task
            The task now uses a custom stylesheet with MCForge elements, and
            configured to combine the generation from the four FML subprojects
            (fmlloader, fmlcore, javafmllanguage, mclanguage) and the Forge project
            into the javadoc output.
            * Update docs/md files, for 1.19 update and the move away from IRC to Discord.
            * Make "Potentially dangerous alternative prefix" a debug warning, not info.
            Co-authored-by: Curle <curle@gemwire.uk>
            Co-authored-by: sciwhiz12 <arnoldnunag12@gmail.com>


```

`CREDITS.txt`
```text
Minecraft Forge: Credits/Thank You

Forge is a set of tools and modifications to the Minecraft base game code to assist 
mod developers in creating new and exciting content. It has been in development for 
several years now, but I would like to take this time thank a few people who have 
helped it along it's way.

First, the people who originally created the Forge projects way back in Minecraft 
alpha. Eloraam of RedPower, and SpaceToad of Buildcraft, without their acceptiance 
of me taking over the project, who knows what Minecraft modding would be today.

Secondly, someone who has worked with me, and developed some of the core features
that allow modding to be as functional, and as simple as it is, cpw. For developing
FML, which stabelized the client and server modding ecosystem. As well as the base
loading system that allows us to modify Minecraft's code as elegently as possible.

Mezz, who has stepped up as the issue and pull request manager. Helping to keep me
sane as well as guiding the community into creating better additions to Forge.

Searge, Bspks, Fesh0r, ProfMobious, and all the rest over on the MCP team {of which 
I am a part}. For creating some of the core tools needed to make Minecraft modding 
both possible, and as stable as can be.
  On that note, here is some specific information of the MCP data we use:
    * Minecraft Coder Pack (MCP) *
      Forge Mod Loader and Minecraft Forge have permission to distribute and automatically 
      download components of MCP and distribute MCP data files. This permission is not 
      transitive and others wishing to redistribute the Minecraft Forge source independently
      should seek permission of MCP or remove the MCP data files and request their users 
      to download MCP separately.
      
And lastly, the countless community members who have spent time submitting bug reports, 
pull requests, and just helping out the community in general. Thank you.

--LexManos

=========================================================================

This is Forge Mod Loader.

You can find the source code at all times at https://github.com/MinecraftForge/MinecraftForge/tree/1.12.x/src/main/java/net/minecraftforge/fml

This minecraft mod is a clean open source implementation of a mod loader for minecraft servers
and minecraft clients.

The code is authored by cpw.

It began by partially implementing an API defined by the client side ModLoader, authored by Risugami.
http://www.minecraftforum.net/topic/75440-
This support has been dropped as of Minecraft release 1.7, as Risugami no longer maintains ModLoader.

It also contains suggestions and hints and generous helpings of code from LexManos, author of MinecraftForge.
http://www.minecraftforge.net/

Additionally, it contains an implementation of topological sort based on that 
published at http://keithschwarz.com/interesting/code/?dir=topological-sort

It also contains code from the Maven project for performing versioned dependency
resolution. http://maven.apache.org/

It also contains a partial repackaging of the javaxdelta library from http://sourceforge.net/projects/javaxdelta/
with credit to it's authors.

Forge Mod Loader downloads components from the Minecraft Coder Pack
(http://mcp.ocean-labs.de/index.php/Main_Page) with kind permission from the MCP team.


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
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide) {
            return;
        }

        ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Player logged in: {}", player.getName().getString());

        MinecraftServer server = player.getServer();
        if (server == null) return;

        ServerLevel level = server.overworld();
        UUID uuid = player.getUUID();
        SpreaderWorldData data = SpreaderWorldData.get(level);

        // A. Existing Assignment Check
        BlockPos existingAssignment = data.getAssignment(uuid);
        if (existingAssignment != null) {
            ChunkPartySpreader.LOGGER.info("[Chunk Party Spreader] - Player already has assignment at: {}", existingAssignment);
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

            if (skipOceans && level.getBiome(biomePos).is(BiomeTags.IS_OCEAN)) {
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
            return charge != null && charge > 0;
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

End CPS Provided Code

---