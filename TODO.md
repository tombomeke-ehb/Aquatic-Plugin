# AquaticSMP: To do

The roadmap for the next versions. Details are still open and will be worked out per feature.

## 0. Rebrand: our own identity
The idea came from an anime: powers from fruits, a sea full of islands, crews competing for one crown. The theme stays (pirates, sea, powers), but every name and its lore must be our own.

**Done in 1.4.0 / 1.5.0:** player-visible names were renamed (the top title is now **Conqueror of the Sea**) (internal ids are unchanged, so existing data keeps working).

| Concept | New name |
| --- | --- |
| Power fruits | **Abyss Fruits** |
| Fruit classes | **Elemental** / Body / Beast |
| The 8 fruits | Springcoil, Emberheart, Rimefrost, Thunderveil, Ghostglass, Riftshift, Hollowvoid Fruit, Behemoth Fruit |
| Fruit-cancelling metal | **Abyssal Iron** (baton and shackles) |
| Swords | Tidecleaver, Nightbloom, Leviathan's Fang |
| Island compass / crew phone | **Tide Compass** / **Echo Conch** |
| Highest title | **Conqueror of the Sea** (Crown of the Conqueror) |
| Bounty titles | Rookie → Notorious → Rising Tide → Corsair Lord → Dread Tyrant |
| Navy enemies | **The Tidewatch** (Recruit, Rifleman, Lieutenant, Warden Captain, High Warden Vex) |
| The sea | **The Endless Sea**; zones: the Shallows → the Open Sea → the Abyssal Reach |
| Spirit power (planned) | **Tidewill**: Ironwill, Seasight, Stormcrown |
| Island names | Generated from our own word lists per island type |

**Still to do:**
- [ ] **Rename the internal ids with a migration**, for example `gomu_gomu` → `springcoil`, `log_pose` → `tide_compass`, `pirate_king_crown` → `sovereign_crown`. Existing items and saved fruit powers have to be converted automatically, through an alias table that maps old ids to new ones.
- [x] Lore for the world, Abyss Fruits, Abyssal Iron, the Tidewatch and the throne; in items, the intro and the guide (done in 1.7.0).
- [ ] **Expand the lore:**
  - Where Abyss Fruits come from: for example, they grow on coral in the deep, and the sea wants its power back, which is why users can't swim.
  - Who the Tidewatch are.
  - What the Conqueror of the Sea means.
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
- [x] Sidebar on the right side of the screen with Doubloons, bounty, crew, rank, the current island and its owner, your fruit power and the running event. Toggle it with `/sidebar`; lines are in `sidebar.yml`. It steps aside during the tournament (done in 1.5.0).
- [x] Current fruit move and its cooldown on the sidebar (done in 1.8.0).
- [ ] Show the active combat tag on the sidebar.
- [ ] Context modes: during an event, show the event score (kills, held time).
- [ ] Hook into the TAB plugin's scoreboard when it's installed.

### 1. Island management and development
- [x] Upgrades from the crew bank (Treasury, Fortress, Watchtower, Harbor), the `/island manage` menu, and losing levels on conquest (done in 1.6.0).
- [x] Barracks (respawn), Wellspring (healing), build access officers-only (done in 1.7.0).
- [ ] More upgrades: a Granary (food/crop drops), a Shipyard (cheaper ships), a Vault (safe crew storage that can't be raided).
- [ ] Permissions per rank in the manage menu (who may build, open chests, use doors).
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
- [x] Harbor Market: categories, buy/sell, custom items, in-game editing with `/shop admin` (done in 1.7.0).
- [ ] Shop NPC or sign at the capital's market square; limited stock and daily deals; selling island upgrades and ship blueprints.
- [ ] **Server shop** (`/shop`) as a chest GUI, with categories: tools, food, ship parts, pirate gear, rare items.
- [ ] Buy and sell for Doubloons. Prices go in a `shop.yml`, and support custom items (`item:<id>`).
- [ ] **Configurable in-game:** admins add items, set prices and change categories through the GUI, without editing YAML.
- [ ] Optional: limited stock, rotating daily deals, rank- or Conqueror of the Sea-only items.
- [ ] Optional: player shops / trading posts on neutral islands.
- [ ] Decide whether the shop replaces or works alongside EssentialsX `/sell` and `/worth`.

### 3. Tidewill: the inner power (rework of the old "Haki" idea)
The idea stays: an inner strength every pirate can train, separate from fruits. Name, lore and mechanics are our own.

**Lore (draft):** sailors who survive long enough on the Endless Sea learn to bend the tide of a fight with sheer will.

**The three forms:**
- [ ] **Ironwill** (everyone can learn it):
  - Toggle with a key. It drains energy while active.
  - Your hits and blocks get harder.
  - It is the only way, besides Abyssal Iron, to hit an **Elemental** fruit user, who otherwise phases through normal hits.
- [ ] **Seasight** (everyone can learn it):
  - Nearby enemies glow through walls for a few seconds.
  - A short dodge window against the next attack.
  - A warning when someone aims a bow or ability at you.
- [ ] **Stormcrown** (rare: **only some players have it**):
  - An area wave of pressure that knocks back and briefly stuns weaker players. "Weaker" means a lower Tidewill level.
  - **Ideas for who gets it:**
    - a small chance at first join
    - earned by a big feat (win the tournament, defeat a High Warden)
    - only crew captains with enough reputation
  - **Discuss:** whether the Conqueror of the Sea should always awaken it.

**Progression:**
- [ ] Tidewill XP from PvP, fighting Tidewatch mobs, using the forms, and training at special places (meditation spots on ancient islands, training dummies).
- [ ] Levels per form unlock longer duration, stronger effects and lower energy cost.
- [ ] Energy bar, shown in the action bar or the sidebar, so the forms can't be used non-stop.
- [ ] `/tidewill`: status, levels, toggles. Admin: set levels, grant or remove Stormcrown.
- [ ] Add an `elemental: true` flag to fruits in `items.yml`, so the phase rule knows which fruits it applies to.

**Balance:** Tidewill must not make fruits useless or fruits make Tidewill useless. A trained non-fruit player should beat an untrained fruit user.

### 4. More and better abilities
**Today's problems:** most abilities only work up close (punch, swap, slash), they feel weak, and they aren't balanced for real PvP.
- [ ] **Range per role:** every fruit gets at least one ranged or zone move, not only melee.
  - Mera: a fire wave or flame pillar at range.
  - Goro: a chained lightning bolt across 20+ blocks.
  - Hie: an ice path that freezes everyone on it.
  - Gomu: Gatling, a fast cone of hits.
- [x] Move sets: 2–3 moves per fruit with separate cooldowns, switched with sneak + scroll (done in 1.8.0).
- [ ] **More moves:** a 3rd and 4th move for every fruit.
  - Pick a move by sneak + scrolling the hotbar, or by holding the fruit "skill item" in a set slot.
  - The current move and its cooldowns show in the action bar.
- [ ] **Combos and synergy:**
  - Hie freezes a target, and Goro does extra damage to frozen targets.
  - Ironwill breaks Elemental defences (see Tidewill).
- [ ] **Mobility moves:** dash, double jump, glide or flight, so fights aren't just standing still.
- [ ] **Elemental passive:** a chance to "phase through" normal hits, countered by Ironwill or Abyssal Iron.
- [ ] **Awakening:** a stronger version of a fruit after a lot of use (mastery XP per fruit).
- [ ] More fruits: Body, Beast and Elemental variety.
- [x] Better visuals: shockwaves, beams, auras, trails, colours (done in 1.3.0).
- [x] Telegraph: `windup-ticks` on any move (done in 1.8.0).
- [ ] **PvP balance pass**, tested with real players:
  - **Time-to-kill target:** an ability should take about 20–30% of a player's health, and a full combo should not one-shot a player in diamond or netherite armour.
  - **Armour:** abilities respect armour and Protection. Decide per move whether it does true damage.
  - **Fair matchups:** a non-fruit player with Tidewill and a good sword should still have a fair chance.
  - **Per-world/region multipliers:** separate damage multipliers for PvP and PvE.
  - **Test arena:** `/aitem test` dummies that show damage numbers.
- [ ] **Ideas to discuss:**
  - Should fruits be rare world events (only one of each fruit exists on the server, and it respawns when the owner dies), or more common?
  - Should eating a fruit cost something, such as losing your current one?

### 4b. Island generation: more unique, with caves
- [x] Plains island type, mountains with rock and snow peaks, cave tunnels with entrances, ore veins (done in 1.5.0).
- [ ] **Better caves:**
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
  - [x] better ore placement (veins, done in 1.5.0)
- [x] Islands from radius 100 up to 1500, generated chunk by chunk (done in 1.3.0).
- [ ] **Performance:** keep the per-tick budget. Caves and structures are generated in the same job system.
- [x] Capital town: roads, houses, piers, lighthouse; coral reefs and kelp (done in 1.6.0).
- [x] Shipwrecks on beaches, freshwater lakes (done in 1.8.0).
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
- [x] Coral Trident, Captain's Greatcoat, Healing Tonic, Grog of the Gale (done in 1.8.0).
- [x] Uncommon tier between common and rare (done in 1.5.0).
- [ ] More unique loot: legendary weapons and armour sets, accessories with passive buffs, ship upgrades, Tidewill training scrolls, rare cosmetics such as hats and titles.
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
  - Building our own (see the Shop section) makes sense if the shop should sell custom items, island upgrades and Tidewill training. My suggestion: build our own small shop for pirate items and upgrades, and leave general block trading to an existing plugin.
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
