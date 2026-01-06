# Chunk Party Spreader (CPS)

Welcome to **Chunk Party Spreader**, the server-side utility that turns your empty void world into an organized, sprawling suburbia.

Instead of piling everyone onto a single chaotic spawn island, CPS acts as a socially anxious bouncer. It assigns every new player their own dedicated chunk in a growing square spiral pattern. It even politely asks the **Chunk By Chunk** mod to generate the land *before* they arrive, so they don't fall into the abyss while loading.

## Key Features
*   **The Spiral:** Assigns home chunks in a deterministic Ulam Spiral pattern (0,0 -> 1,0 -> 1,1 -> ...).
*   **Void Stasis:** Catches players on join, floats them safely in the sky (Y=320), and only lets them drop once their land is generated and solid.
*   **Hydrophobic Logic:** (Configurable) If a player's assigned coordinate lands in an **Ocean** or **River**, the mod says "Absolutely not," skips that index, and finds them dry land.
*   **Persistence:** Remembers exactly where every player "lives." If they die without a bed, they respawn at their personal chunk, not world spawn.
*   **Chunk By Chunk Integration:** Directly interfaces with CBC to trigger single-chunk generation commands.
*   **Side:** Server-Side Only (Clients do not need to install this).

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