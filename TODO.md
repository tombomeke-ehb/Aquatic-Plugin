# AquaticSMP: To do

**Aquatic SMP: Sail. Claim. Conquer.**

This file is the roadmap. Tick off items as they're finished and add new ideas under the right heading.
What changed in each version is in [CHANGELOG.md](CHANGELOG.md). Current version: **2.14.0**.

---

## Done (up to 2.14.0)

**World and islands**
- Procedural islands, generated chunk by chunk without lag, in size classes (small to large).
- 11 types, each with a rarity:
  - Plains and Forest: common
  - Jungle, Snowy, Desert and Village: uncommon
  - Tropical and Tidewatch: rare
  - Volcanic and Ancient: epic
  - Capital: legendary
- Terrain: mountains, real caves and ores on every island, ore veins, coral reefs and kelp, lakes, shipwrecks, and volcano craters with lava streams.
- The capital is the spawn town (`hub` module):
  - Shopkeeper villagers in market stalls, the crew registrar and a live bounty board.
  - The founders' monument, a fountain, plaza decoration and a timed parkour tower.
  - Harbour roads, houses, piers and a lighthouse.
- **Tidewatch outposts:** forts with a garrison in uniform (illagers with their own textures) and a Warden Captain.
  - Liberate one to claim it.
  - About 1 in 5 officers has a fruit power, with a warning before each attack.
- **Villages:** neutral, unclaimable islands with cottages, a smithy, fishing huts, a tavern, farm fields and a well.
- **Quests:** villagers around each village well give repeatable quests (fish, hunt, bring materials) for Doubloons, XP and items.
- Unique island names, discovery rewards by rarity, `/island home [island]`, and `/island admin cancel`.

**Crews and territory**
- Crews with ranks, bank, home, flags (designer and presets), alliances, wars, reputation and a `/crew` menu.
- Claiming, and conquest by holding the flag. Flags are unbreakable, and `/tpa` is crew-only.
- Island management (`/island manage`):
  - 8 upgrades: Treasury, Fortress, Watchtower, Harbor, Barracks, Wellspring, Vault and Granary.
  - Officers-only build access.
  - Upgrades lose a level when the island is conquered.
- **Facilities:** six buildings a crew places on its island: Watchtower, Forge, Farm Field, Dock, Storehouse and Lighthouse.
  - A ghost preview first, then [Rotate] / [Move here] / [Build].
  - Built with an animation and a progress bar.
  - Most give an income bonus. Demolishing gives a refund.
- **Schematics:** builds from `.schem` (WorldEdit/FAWE) and `.nbt` (structure block) files:
  - Admins can preview, paste (animated or at once) and undo them.
  - They can be placed automatically on every new island of a type (the spawn town on the capital).
  - Crews can buy them as their own facilities.

**Powers and combat**
- 14 Abyss Fruits, each with 2–3 moves (sneak + scroll).
  - 13 ability types, with windups, mastery levels (1–20) and extra hearts.
  - Fruits are their own module and can be switched off.
  - The Seafoam Milkshake removes a fruit power.
- Pirate levels (1–50) with level rewards; bounty tiers make you stronger.
- Weapons:
  - Swords: Tidecleaver, Nightbloom, Leviathan's Fang, Storm Saber, Tide Rapier, Abyss Cutlass and Coral Scimitar.
  - Heavy weapons: Kraken's Maul, Abyssal Anchor and the Whaler's Harpoon.
  - Guns: the Flintlock, the Musket and the Hand Cannon.
  - Three bombs, the Marauder's Buckler and the Abyssal Shackles.
- Tools: the Deepcore Pickaxe and the Tide Compass. Armour sets: Tidewarden and Corsair, with set bonuses.
- The smithy upgrades weapons, armour, tools and bows +1..+5. Custom recipes are shown in the recipe book and `/recipes`.
- Combat tag (no travelling in combat), respawn protection and new-player protection.

**Looks (resource pack)**
- Custom textures for every custom item.
  - 3D models for the guns.
  - Animated fruits that preview their power, and animated shine on the weapons.
  - Armour that looks different when worn.
  - Tidewatch illager skins.
- Animated 3D effects (slashes, shockwaves, flashes) and better particles.
- The plugin sends the pack itself. Each jar knows its own pack link and SHA-1, so `server.properties` needs nothing.

**Economy and progression**
- Doubloons, bounties, and treasure in 5 tiers:
  - Rebalanced loot, with no totems.
  - Unique treasure maps that never reroll, sold to 2–3 players per treasure.
- The Harbor Market (editable in-game). Players open it only at the shopkeepers at spawn; ops can still use `/shop`.

**Events and community**
- 7 server events with an optional auto-scheduler, and the Crown Tournament for the Conqueror of the Sea.
- An intro, a starter kit, tips, the guide book, lore and `/discord`.
- The sidebar, tab list, chat hover cards and clickable help.
- LuckPerms/Vault rank prefix and suffix in chat and the tab list.
- Config versions: new settings reach existing servers automatically, with backups.

**Identity**
- Our own names and lore (Abyss Fruits, the Endless Sea, the Tidewatch, the Conqueror of the Sea). No One Piece names left.

---

## Next up (priority)

1. [ ] **Test in a real game client.** Bots tested everything they could, but nobody has looked at these yet:
   - The textures and animations: fruits, weapon shine, 3D effects, Tidewatch skins.
   - A village and its quest villagers.
   - Facility previews (ghost blocks, red glass, the outline) and the build animation.
   - Sneak-triggered abilities. Bots can't sneak, so moves like the Abyssal Anchor's Anchor Drop are untested.
   - That the plugin-sent resource pack prompt shows once and loads.
2. [ ] **Balance in-game:**
   - Time-to-kill with fruit damage ×1.5, mastery, bounty power and levels together (`abilities.fruit-damage-multiplier`, mastery values, `power-perks`).
   - The officer power chance (`tidewatch.power-chance`).
   - Quest rewards and cooldowns (`quests.yml`).
   - Facility prices and income bonuses (`facilities` in `islands.yml`).
3. [ ] **Tidewill** (our version of the inner-power idea): Ironwill, Seasight and Stormcrown. Details below.
4. [ ] **Elemental passive:** Elemental fruit users phase through normal hits. Only Ironwill or Abyssal Iron gets through. This makes Tidewill matter.
5. [ ] **Owner's wishlist:**
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
- [x] Four more fruits (done in 2.9.0), animated fruit textures and held-fruit particles (done in 2.10.0 and 2.11.0).
- [x] Officers with fruit powers (done in 2.9.0), only 1 in 5 of them (done in 2.11.0).
- [ ] More fruits for every class: Body, Beast and Elemental.
- [ ] Fruit rarity: only one of each fruit exists on the server, and it respawns in treasure when its owner dies. **Discuss.**
- [ ] Combo synergy: frozen targets take extra lightning damage, burning targets take extra wind damage, and so on.
- [x] Animated 3D effects and better particles for fruits and weapons (done in 2.9.1 and 2.10.0).
- [ ] Per-fruit hit sounds, and screen shake for big moves (a short slowness and blindness pulse).
- [ ] Damage test dummies (`/aitem test`) that show damage numbers, for balancing.

### Gear
- [x] Swords, bombs, buckler, and the Tidewarden and Corsair armour sets with recipes.
- [x] Smithy: upgrade weapons, armour, tools and bows +1..+5 (done in 2.6.0).
- [x] Guns: Flintlock, Hand Cannon (3D models, 2.8.0) and the Musket (2.10.0), with muzzle flash, tracer and impact effects.
- [x] Harpoon, Coral Scimitar, Abyssal Anchor and Deepcore Pickaxe (done in 2.11.0).
- [ ] Accessories (ring and amulet slots, through the off hand or a menu) with small passive buffs.
- [ ] A grappling or boarding hook for boarding ships and getting up cliffs.
- [ ] More armour sets: make the Tidewatch uniform (worn by the garrison since 2.9.0) a set players can earn, and an Abyssal set (late game).

---

## World and islands
- [x] Rarity, Forest, mountains, caves, ore veins, reefs, lakes, shipwrecks, the capital town, and lava streams.
- [ ] **Rarer islands have better rewards:** more and better treasure, rare ores, and more income.
- [x] Tidewatch outposts: fort islands with a garrison and a Warden Captain (done in 2.6.0).
- [x] Real caves and ores on every island, bigger islands, better spawn town (done in 2.4.0 to 2.7.1).
- [x] Village islands (done in 2.12.0).
- [ ] **Structures from schematics:** pirate camps, temples on desert islands, and Runestones with lore and clues on ancient islands.
- [ ] Sea caves you can sail into, waterfalls, and rivers.
- [ ] Special islands: a floating sky island, a ghost island that only appears at night, and a giant tree island.
- [ ] **Sea zones:** the Shallows → the Open Sea → the Abyssal Reach. Farther from spawn means harder and richer, shown on the sidebar and on maps.
- [ ] **Custom map:** a "Chart of the Endless Sea" item that only shows islands you've discovered, plus a wall map at spawn. Optionally BlueMap per crew.

## Island management and development
- [x] 6 upgrades, the `/island manage` menu, build access, and losing a level on conquest.
- [x] Facilities: buildings placed with a preview and built with an animation (done in 2.13.0).
- [ ] More facilities (shipyard, tavern, crew hall) and upgrading a facility to a bigger version.
- [x] Unbreakable flags (only conquest or `/island admin removeflag`), crew-only `/tpa` (done in 2.2.0).
- [x] Placeable buildings with a preview and a bonus: done as facilities in 2.13.0.
- [x] Builds from schematic files (.schem and .nbt): admin preview/paste/undo, automatic builds per island type, and facilities from schematics (done in 2.14.0).
- [ ] Schematics: copy chest contents, sign text and banners (block entities), and entities (item frames, armour stands).
- [ ] Schematics: replace parts of the generated capital (turn off the market, monument or parkour tower per build) so a team build can take their place.
- [ ] Schematics: an in-game save command (select two corners, save to the folder) for servers without WorldEdit.
- [ ] Link facilities to upgrades (for example the Vault upgrade needs a Storehouse), if we want that.
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
- [x] LuckPerms/Vault rank prefix and suffix in chat and tab (done in 2.10.0).
- [ ] An admin config menu (`/aquatic config`) to change the main settings in-game.
- [ ] Edit loot in-game: hold an item and run `/treasure loot add <tier> [weight]`.

## Rebrand (still open)
- [x] Rename the internal ids, with a migration (done in 2.3.1).
- [ ] Rename `/hallofkings` → `/hallofconquerors`, keeping the old name as an alias for a while.
- [x] Resource pack with custom textures and models, sent by the plugin (done in 2.7.1 to 2.12.1).
- [ ] A logo and our own colour palette in the messages.
- [ ] More lore: the Harbor Crown, the High Warden, and the history of the Abyss.

## Technical
- [x] Release flow: build the resource pack before the jar (it bakes the pack's SHA-1 into the plugin), reproducible pack builds (done in 2.12.1).
- [ ] Automated tests for the core, the economy and conquest.
- [ ] Shared API: events for alliances, peace, rank changes, unclaiming and conquest starts.
- [ ] Remove stat bonuses (health, damage) from offline players when a module is permanently turned off.
- [ ] Known limitations:
  - PvP is allowed in tournament matches even on neutral islands.
  - A totem doesn't work in matches.
  - Right-clicking empty air doesn't trigger fruit moves (sneak + F does).
  - Facility demolish leaves the foundation columns under the building.
  - Facilities are not saved half-built: a server crash mid-construction leaves it half done (a normal stop finishes it).
