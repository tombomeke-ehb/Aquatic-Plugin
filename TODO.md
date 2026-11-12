# AquaticSMP: To do

The roadmap for the next versions. Details are still open and will be worked out per feature.

## 0. Rebrand: our own identity
The idea came from an anime: powers from fruits, a sea full of islands, crews competing for one crown. The theme stays (pirates, sea, powers), but every name and its lore must be our own.

**Done in 1.4.0:** player-visible names were renamed (internal ids are unchanged, so existing data keeps working).

| Concept | New name |
| --- | --- |
| Power fruits | **Abyss Fruits** |
| Fruit classes | **Elemental** / Body / Beast |
| The 8 fruits | Springcoil, Emberheart, Rimefrost, Thunderveil, Ghostglass, Riftshift, Hollowvoid Fruit, Behemoth Fruit |
| Fruit-cancelling metal | **Abyssal Iron** (baton and shackles) |
| Swords | Tidecleaver, Nightbloom, Leviathan's Fang |
| Island compass / crew phone | **Tide Compass** / **Echo Conch** |
| Highest title | **Sea Sovereign** (Crown of the Sea Sovereign) |
| Bounty titles | Rookie → Notorious → Rising Tide → Corsair Lord → Dread Tyrant |
| Navy enemies | **The Tidewatch** (Recruit, Rifleman, Lieutenant, Warden Captain, High Warden Vex) |
| The sea | **The Endless Sea**; zones: the Shallows → the Open Sea → the Abyssal Reach |
| Spirit power (planned) | **Resolve**: Iron Resolve, Keen Resolve, Sovereign Resolve |
| Island names | Generated from our own word lists per island type |

**Still to do:**
- [ ] **Rename the internal ids with a migration**, for example `gomu_gomu` → `springcoil`, `log_pose` → `tide_compass`, `pirate_king_crown` → `sovereign_crown`. Existing items and saved fruit powers have to be converted automatically, through an alias table that maps old ids to new ones.
- [ ] **Write our own lore:**
  - Where Abyss Fruits come from: for example, they grow on coral in the deep, and the sea wants its power back, which is why users can't swim.
  - Who the Tidewatch are.
  - What the Sea Sovereign means.
  - Short lore lines on every item.
- [ ] **New ability names** per fruit that fit the new theme. A few still describe the effect generically ("Ice Age", "Beast Form").
- [ ] **Rename the code itself** (classes, permissions, commands where needed), for example `PirateKingCrownedEvent` → `SovereignCrownedEvent` and the `/hallofkings` command → `/hallofsovereigns`. Keep the old names as aliases for a while.
- [ ] **Fresh look:**
  - a plugin and server logo
  - our own colour palette in the messages
  - unique custom model data, plus a resource pack later
- [ ] Go through all texts once more for anything that still sounds like the anime.
- [ ] Decide the final server name and tagline (now "Aquatic SMP - Sail. Claim. Conquer.").

## Planned features

### Sidebar (scoreboard HUD)
- [ ] A sidebar on the right side of the screen showing:
  - your Doubloons, crew, rank and bounty
  - the island you're on and its owner
  - your fruit power and its cooldown
  - the current server event with its time left
- [ ] Toggle it with `/sidebar`. Lines are configured in a `sidebar.yml`.
- [ ] Context modes: during a tournament it shows the standings, during an event the event score, and normally the player info.
- [ ] Works next to the TAB plugin: when TAB is installed, use its scoreboard or turn ours off.
- [ ] No flicker: update every second with scoreboard teams, not by recreating the board.


### 1. Island management and development
Owned islands should become something a crew builds up over time, not just a claim.
- [ ] **Island levels / upgrades** bought with Doubloons from the crew bank. For example: more passive income, a bigger protected radius, a stronger conquest defence (longer capture time), and more members allowed to build.
- [ ] **Island buildings or "facilities"** that each give a bonus:
  - a dock: faster boats, or a respawn point
  - a market: extra income
  - a watchtower: alerts when enemies land
  - a vault: a safe crew storage
- [ ] **Resources per island type**, for example volcanic → ores, jungle → wood/food, desert → treasure chance.
- [ ] **`/island manage` GUI** (chest menu) to see stats, buy upgrades, set permissions per rank and collect income.
- [ ] **Island stats:** income history, visitors, times attacked.
- [ ] Decide what happens to upgrades when an island is conquered: keep them, lose some, or the conqueror gets them.

### 2. Shop
- [ ] **Server shop** (`/shop`) as a chest GUI, with categories: tools, food, ship parts, pirate gear, rare items.
- [ ] Buy and sell for Doubloons. Prices go in a `shop.yml`, and support custom items (`item:<id>`).
- [ ] **Configurable in-game:** admins add items, set prices and change categories through the GUI, without editing YAML.
- [ ] Optional: limited stock, rotating daily deals, rank- or Sea Sovereign-only items.
- [ ] Optional: player shops / trading posts on neutral islands.
- [ ] Decide whether the shop replaces or works alongside EssentialsX `/sell` and `/worth`.

### 3. Resolve
- [ ] **Iron Resolve:** a toggle that adds damage and lets you **hit Elemental abyss fruit users**. A Elemental user (for example `mera_mera`, `goro_goro` or `hie_hie`) should dodge normal hits unless the attacker uses Resolve or Abyssal Iron.
- [ ] **Keen Resolve:** sense nearby players through walls (glowing), a short dodge window, and warnings when someone targets you.
- [ ] **Sovereign Resolve:** **only some players have it**, either at random on first join, or rare and earned. It is an area blast that knocks back and stuns weaker players, and its power scales with your Resolve level.
- [ ] **Training and progression:** Resolve XP from fighting, using Resolve, training dummies or special training islands. Levels unlock longer duration, more power and lower cooldowns.
- [ ] Resolve energy / stamina bar, so it can't be used non-stop.
- [ ] `/resolve` command: status, levels and toggles, plus admin commands to set levels or grant Sovereign Resolve.
- [ ] Add a "elemental" flag to abyss fruits in `items.yml`, so the dodge rule knows which fruits it applies to.

### 4. More and better abilities
**Today's problems:** most abilities only work up close (punch, swap, slash), they feel weak, and they aren't balanced for real PvP.
- [ ] **Range per role:** every fruit gets at least one ranged or zone move, not only melee.
  - Mera: a fire wave or flame pillar at range.
  - Goro: a chained lightning bolt across 20+ blocks.
  - Hie: an ice path that freezes everyone on it.
  - Gomu: Gatling, a fast cone of hits.
- [ ] **Move sets:** 3–4 moves per fruit, each with its own cooldown.
  - Pick a move by sneak + scrolling the hotbar, or by holding the fruit "skill item" in a set slot.
  - The current move and its cooldowns show in the action bar.
- [ ] **Combos and synergy:**
  - Hie freezes a target, and Goro does extra damage to frozen targets.
  - Resolve breaks Elemental defences (see Resolve).
- [ ] **Mobility moves:** dash, double jump, glide or flight, so fights aren't just standing still.
- [ ] **Elemental passive:** a chance to "phase through" normal hits, countered by Resolve or Abyssal Iron.
- [ ] **Awakening:** a stronger version of a fruit after a lot of use (mastery XP per fruit).
- [ ] More fruits: Body, Beast and Elemental variety.
- [x] Better visuals: shockwaves, beams, auras, trails, colours (done in 1.3.0).
- [ ] A telegraph before big hits so they can be dodged.
- [ ] **PvP balance pass**, tested with real players:
  - **Time-to-kill target:** an ability should take about 20–30% of a player's health, and a full combo should not one-shot a player in diamond or netherite armour.
  - **Armour:** abilities respect armour and Protection. Decide per move whether it does true damage.
  - **Fair matchups:** a non-fruit player with Resolve and a good sword should still have a fair chance.
  - **Per-world/region multipliers:** separate damage multipliers for PvP and PvE.
  - **Test arena:** `/aitem test` dummies that show damage numbers.
- [ ] **Ideas to discuss:**
  - Should fruits be rare world events (only one of each fruit exists on the server, and it respawns when the owner dies), or more common?
  - Should eating a fruit cost something, such as losing your current one?

### 4b. Island generation: more unique, with caves
- [ ] **Caves:**
  - Cave systems under islands, using 3D noise worms or "spaghetti" tunnels, plus a few big chambers.
  - Entrances on the beach and the cliffs.
  - Treasure can spawn in caves (deeper means a better tier).
- [ ] **Sea caves and grottos:** half-flooded caves you can sail a boat into, good for hidden bases.
- [ ] **More shapes:**
  - atolls (a ring with a lagoon)
  - archipelagos (several small islets)
  - cliff islands (tall rock with a small beach)
  - sandbars
  - sky pillars (very tall rock spires)
- [ ] **Landmarks per type:**
  - Jungle: waterfalls.
  - Volcanic: lava rivers and an obsidian lava cave.
  - Snowy: ice spikes.
  - Desert: an oasis and a buried temple.
  - Ancient: a ruined city with a "Runestone" block that gives lore and a clue.
- [ ] **Structures:**
  - shipwrecks on the shore
  - pirate camps (with mobs)
  - a Tidewatch outpost (mini-boss)
  - lighthouses
  - docks on neutral islands
  - Templates are loaded from `.schem` files (WorldEdit schematics) so builders can add their own.
- [ ] **Rare special islands:** a sky island (floating), a ghost island at night, a giant tree island.
- [ ] **Biome mixing:** two biomes on one big island (for example a jungle centre and a volcanic peak).
- [ ] **Better looks:**
  - smoother coasts and reefs (coral and kelp in the shallows)
  - rivers and lakes
  - custom tree shapes for palms and big jungle trees
  - better ore placement (veins)
- [x] Islands from radius 100 up to 1500, generated chunk by chunk (done in 1.3.0).
- [ ] **Performance:** keep the per-tick budget. Caves and structures are generated in the same job system.
- [ ] **Placeable facilities on big islands:** buy a building (dock, market, watchtower, vault) in the shop and place it as a schematic, with a preview outline, on your own island. Each building gives its upgrade. See section 1.

### 4c. Custom map
- [ ] **In-game map item:** a "Endless Sea chart".
  - It only shows islands you have **discovered**, plus your crew's territory in its colour, and treasure rumours.
  - Built with the Bukkit map API (map renderer). It can be a 2×2 or 3×3 wall of item frames at spawn.
- [ ] **Web map (BlueMap):**
  - Islands are coloured by owner, with popup info (type, owner, level).
  - Optional: hide undiscovered islands per player, which needs BlueMap's player-specific markers, or one map per crew.
- [ ] **Sea zones:** the map is divided into named seas (the Shallows, Endless Sea, Abyssal Reach). Further from spawn means harder islands and better loot. Shown on the map and in an action bar when you sail into a new sea.
- [ ] **Navigation:** the Tide Compass "locks" onto an island you choose (`/logpose set <island>`). An Bound Compass item points to one fixed island.

### 5. More treasure items
- [ ] More unique loot: legendary weapons and armour sets, accessories with passive buffs, ship upgrades, Resolve training scrolls, rare cosmetics such as hats and titles.
- [ ] Item rarities shown in the name and lore.
- [ ] Set bonuses for wearing matching pirate gear.

### 6. In-game configuration
- [ ] **Admin GUI** (`/aquatic config`) to change the most-used settings in-game: module toggles, costs, cooldowns, treasure loot, shop prices and item stats. Changes are saved to the YAML files.
- [ ] Edit loot tables in-game: hold an item → `/treasure loot add <tier> [weight]`.
- [ ] Create custom items in-game: hold an item → `/aitem create <id>`, then set its type and ability.

## Still open from before

- [ ] **Test in-game** everything that needs players:
  - crews, claiming and conquest
  - Abyss Fruits and gear
  - tournament matches
  - treasure maps
  - PvP events, combat tag, starter kit and tips
- [ ] **Ships (decision first):** the concept document mentions **Movecraft** for ships. It has no shop plugin, so the shop above is ours to build (or another plugin, see below).
  - **Option A: integrate Movecraft.**
    - Listen to its craft events, so a ship is owned by a crew and only crew members can pilot it.
    - Sinking an enemy ship gives reputation, and Naval Battle counts real ships.
    - Ship blueprints can be bought in the shop.
    - Check first: is there a Movecraft build for Paper 1.21.4, and does it run on the server host?
  - **Option B: our own simple ships.**
    - Boat-based "ships": a boat with upgrades such as speed, cannons and storage, as items from the shop.
    - Much less work than moving block ships, but less impressive.
  - **Option C: ship schematics.** Spawn a pre-built ship at a dock that doesn't move, used as a base and for naval battles. Can be combined with Movecraft later.
  - My suggestion: try Movecraft first (A). If it doesn't run well on 1.21.4, go for B.
- [ ] **Shop plugin or our own?**
  - Existing options: EssentialsX `/sell` and `/worth` (simple), or a GUI shop plugin such as EconomyShopGUI or ShopGUIPlus. These work with our Doubloons through Vault.
  - Building our own (see the Shop section) makes sense if the shop should sell custom items, island upgrades and Resolve training. My suggestion: build our own small shop for pirate items and upgrades, and leave general block trading to an existing plugin.
- [ ] **Personal world map:** only shows islands you have discovered (BlueMap currently shows everything).
- [ ] **Seasons:** a command to start a new season, resetting bounties, reputation and/or islands.
- [ ] **Known limitations:**
  - PvP is allowed in tournament matches even on neutral islands.
  - A totem of undying doesn't save you in a tournament match.
  - Fruit abilities can't be triggered by right-clicking empty air (use sneak + F).
  - The offline-raid grace period resets after a restart.
- [ ] **Shared API:** add events for alliance, peace, rank change, island unclaim and conquest start, plus economy transfer and name lookup.
- [ ] **Automated tests** for the core and the most important rules.
- [ ] **Messages after an update:** improved messages don't show on servers whose config already has the old text. Consider a "config version" with automatic migration.
