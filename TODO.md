# AquaticSMP: To do

**Aquatic SMP: Sail. Claim. Conquer.**

This file is the roadmap. Tick off items as they're finished and add new ideas under the right heading.

---

## Done (up to 2.0.0)

**World and islands**
- Procedural islands, generated chunk by chunk without lag, 100–1000 blocks across, in size classes.
- 9 types, each with a rarity:
  - Plains and Forest: common
  - Jungle, Snowy and Desert: uncommon
  - Tropical: rare
  - Volcanic and Ancient: epic
  - Capital: legendary
- Terrain: mountains, caves, ore veins, coral reefs and kelp, lakes, shipwrecks, and volcano craters with real lava streams.
- The capital is a harbour town with roads, houses, piers and a lighthouse.
- Unique generated island names, discovery rewards (by rarity), the exact spawn point, and a working `/island admin cancel`.

**Crews and territory**
- Crews with ranks, bank, home, flags (designer and presets), alliances, wars and reputation.
- Claiming and conquest by holding the flag.
- Island management with 6 upgrades (Treasury, Fortress, Watchtower, Harbor, Barracks, Wellspring) and officers-only build access.

**Powers and combat**
- 10 Abyss Fruits with 2–3 moves each (sneak + scroll), 10 ability types (including wave, beam and chain), windups, mastery levels (1–20) and extra hearts.
- Pirate levels (1–50); bounty tiers make you stronger.
- 7 swords, guns and a cannon, 3 bombs, a buckler, and 2 armour sets with recipes and set bonuses.
- Combat tag, respawn protection and new-player protection.

**Economy and progression**
- Doubloons, bounties, treasure (5 tiers) with maps, and the Harbor Market (at the shopkeepers at spawn, editable in-game).
- Neutral village islands with quest-giving villagers.

**Events and community**
- 7 server events and the Crown Tournament for the Conqueror of the Sea.
- Intro, starter kit, tips, the guide book and lore.
- Sidebar, tab list, chat hover cards, clickable help, and `/aquatic resetconfig`.

**Identity**
- Our own names and lore (Abyss Fruits, the Endless Sea, the Tidewatch, Conqueror of the Sea).

---

## Next up (priority)

1. [ ] **Test 2.0.0 in-game and balance it.** Fruit damage ×1.5 plus mastery, bounty power and levels all stack. Measure the time-to-kill in practice and adjust `abilities.fruit-damage-multiplier`, the mastery values and `power-perks`.
2. [ ] **Tidewill** (our version of the inner-power idea): Ironwill, Seasight and Stormcrown. Details below.
3. [ ] **Elemental passive:** Elemental fruit users phase through normal hits. Only Ironwill or Abyssal Iron gets through. This makes Tidewill matter.
4. [x] Rename the internal ids, with a migration (done in 2.3.1).
5. [x] Config versions: new defaults reach existing servers automatically (done in 2.4.0).
6. [ ] **Owner's wishlist:**
   - Weapons named after each of the team (we need the names and who wants what kind of weapon).
   - A weekly event and a monthly Conqueror of the Sea event, run automatically by the scheduler.
   - Builds: a tournament colosseum island, a duel colosseum, and a Christmas island for December.
   - Decide what the world map should show (BlueMap: all islands, or only discovered ones, crew colours, ...).

---

## Powers and combat

### Tidewill (rework of the old inner-power idea)
- [ ] **Ironwill** (everyone can learn it): a toggle that drains energy. Harder hits, and the only way besides Abyssal Iron to hit Elemental users.
- [ ] **Seasight** (everyone can learn it): nearby enemies glow, a short dodge window, and a warning when someone aims at you.
- [ ] **Stormcrown** (rare, only a few players have it): an area wave that knocks back and briefly stuns weaker players (those with a lower Tidewill level).
  - **Who gets it:** a small chance on first join, earned through a big feat, or awakened by the Conqueror of the Sea. Decide which.
- [ ] Tidewill XP from fighting and training (training spots on ancient islands, dummies), levels, an energy bar, and a `/tidewill` command.

### Fruits and abilities
- [x] Move sets, windups, mastery, extra hearts, and the wave/beam/chain types.
- [ ] A 4th move per fruit that unlocks at mastery 10, as an "awakening" ultimate.
- [ ] More fruits for every class: Body, Beast and Elemental.
- [ ] Fruit rarity: only one of each fruit exists on the server, and it respawns in treasure when its owner dies. **Discuss.**
- [ ] Combo synergy: frozen targets take extra lightning damage, burning targets take extra wind damage, and so on.
- [ ] Better visuals: per-fruit hit sounds, and screen shake for big moves (a short slowness and blindness pulse).
- [ ] Damage test dummies (`/aitem test`) that show damage numbers, for balancing.

### Gear
- [x] Swords, bombs, buckler, and the Tidewarden and Corsair armour sets with recipes.
- [x] Smithy: upgrade weapons, armour, tools and bows +1..+5 (done in 2.6.0).
- [ ] Accessories (ring and amulet slots, through the off hand or a menu) with small passive buffs.
- [ ] A grappling or boarding hook for boarding ships and getting up cliffs.
- [ ] More armour sets: a Tidewatch set (from Tidewatch enemies), an Abyssal set (late game).

---

## World and islands
- [x] Rarity, Forest, mountains, caves, ore veins, reefs, lakes, shipwrecks, the capital town, and lava streams.
- [ ] **Rarer islands have better rewards:** more and better treasure, rare ores, and more income.
- [x] Tidewatch outposts: fort islands with a garrison and a Warden Captain (done in 2.6.0).
- [ ] **Structures from schematics:** pirate camps, temples on desert islands, and Runestones with lore and clues on ancient islands.
- [ ] Sea caves you can sail into, waterfalls, and rivers.
- [ ] Special islands: a floating sky island, a ghost island that only appears at night, and a giant tree island.
- [ ] **Sea zones:** the Shallows → the Open Sea → the Abyssal Reach. Farther from spawn means harder and richer, shown on the sidebar and on maps.
- [ ] **Custom map:** a "Chart of the Endless Sea" item that only shows islands you've discovered, plus a wall map at spawn. Optionally BlueMap per crew.

## Island management and development
- [x] 6 upgrades, the `/island manage` menu, build access, and losing a level on conquest.
- [x] Unbreakable flags (only conquest or `/island admin removeflag`), crew-only `/tpa` (done in 2.2.0).
- [ ] **Placeable buildings:** buy a dock, market, watchtower or vault as a schematic and place it on your island with a preview outline. The building gives its bonus.
- [x] Vault and Granary upgrades (done in 2.6.0).
- [ ] Shipyard upgrade (ships).
- [ ] Permissions per rank (build, chests, doors, redstone) in the manage menu.
- [ ] Island statistics: income history, visitors, times attacked.

## Ships
- [ ] **Decide first:** integrate Movecraft (ships owned by a crew, only the crew can pilot them, sinking one gives reputation, naval battles with real ships) or build our own ships from upgraded boats. Check first whether Movecraft runs on Paper 1.21.4.

## Economy and shop
- [x] Harbor Market with in-game editing.
- [x] Shopkeeper villagers in market stalls on the capital (done in 2.4.0).
- [x] The shop only opens at the shopkeepers, not with `/shop` (done in 2.12.0).
- [ ] Limited stock and daily deals.
- [ ] Player shops and trading posts on neutral islands.
- [ ] Sell island upgrades, buildings and ship blueprints in the market.

## Villages and quests
- [x] Neutral village islands (smithy, fishing huts, tavern, farms) with quest givers (done in 2.12.0).
- [ ] Quest chains (one quest unlocks the next) and a reputation per village.
- [ ] Delivery quests between villages and the capital.

## Progression
- [x] Pirate levels, fruit mastery, and bounty power.
- [x] Level rewards with Doubloons, items and titles (done in 2.6.0).
- [ ] Cosmetics, and access to places (for example the Abyssal Reach only from level 20).
- [ ] Seasons: `/aquatic season new` resets bounties, levels and/or islands, keeping the Hall of Kings.
- [ ] Achievements ("Discover 25 islands", "Win a war") with rewards.

## Interface
- [x] Sidebar, tab list, hover cards and help pages.
- [x] `/spawn` with warmup; chat with level badge, @mentions and `[item]`; economy commands win over EssentialsX (done in 2.1.0).
- [x] Expanded sidebar: XP bar, bounty title, crew online, island rarity, combat tag (done in 2.3.0).
- [ ] Tidewill energy on the sidebar.
- [x] A menu for `/crew` (done in 2.6.0).
- [ ] An admin config menu (`/aquatic config`) to change the main settings in-game.
- [ ] Edit loot in-game: hold an item and run `/treasure loot add <tier> [weight]`.

## Rebrand (still open)
- [x] Rename the internal ids, with a migration (done in 2.3.1).
- [ ] Rename `/hallofkings` → `/hallofconquerors`, keeping the old name as an alias for a while.
- [ ] A logo, our own colour palette in the messages, and a resource pack (custom model data is already ready).
- [ ] More lore: the Harbor Crown, the High Warden, and the history of the Abyss.

## Technical
- [ ] Automated tests for the core, the economy and conquest.
- [ ] Shared API: events for alliances, peace, rank changes, unclaiming and conquest starts.
- [ ] Remove stat bonuses (health, damage) from offline players when a module is permanently turned off.
- [ ] Known limitations:
  - PvP is allowed in tournament matches even on neutral islands.
  - A totem doesn't work in matches.
  - Right-clicking empty air doesn't trigger fruit moves (sneak + F does).
