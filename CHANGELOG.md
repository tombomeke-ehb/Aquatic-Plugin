# Changelog

## 1.6.0 (2026-11-12)

### Added
- **Island management:** crews develop their islands with upgrades bought from the crew bank. Only the Captain and First Mate can buy them.
  - **Treasury:** +25% passive income per level (5 levels).
  - **Fortress:** +20% capture time for attackers per level (5 levels).
  - **Watchtower:** the crew is warned when enemies land. From level 2, intruders glow (3 levels).
  - **Harbor:** crewmates swim faster around the island. At level 3 they can also breathe underwater (3 levels).
  - **`/island manage`** opens a menu with the island's stats (size, owner, income, crew bank) and upgrade buttons. `/island upgrade <type>` buys directly.
  - **Conquest:** when an island is conquered, every upgrade loses a level by default (`upgrades.on-conquest`: keep, lose-one or reset).
  - Prices, effects and levels are configurable under `upgrades:` in `islands.yml`.
- **Capital town:** capital islands get a harbour town around the plaza:
  - 4 paved roads to the coast with lamp posts
  - up to 16 houses along the roads, with windows, a door facing the road and a slab roof
  - wooden piers where the roads reach the sea
  - a red-and-white lighthouse
  - Configure it with `town:` in the CAPITAL section.
- **Coral reefs and sea life:** warm islands (tropical, plains, jungle) get coral reefs and seagrass in the shallows, and colder ones kelp forests. Set it per type with `sea-life:`.

## 1.5.0 (2026-11-12)

### Added
- **Sidebar** (new `sidebar` module):
  - A stats panel on the right of the screen: Doubloons, bounty, crew and rank, the island you're on and its owner (or "Open sea" with coordinates), your fruit power, and the running event with its time left.
  - Lines update in place, without flicker.
  - It steps aside while the tournament shows its standings.
  - Players toggle it with `/sidebar`. Every line is configurable in `sidebar.yml`.
- **Plains islands:** a new island type with open grassland, flowers and a few oaks and birches, good building ground. Its names come from a new list, such as "Windmere Downs" or "Clover Fields".
- **Mountains:**
  - Ridges rise in parts of bigger islands, away from the coast.
  - High ground becomes bare rock, and the highest peaks get snow caps.
  - Small islands get small mountains. Per island type, `mountains: 0..2` scales them.
- **Caves:** tunnel systems inside islands (radius 60 and up), with openings to the surface here and there.
  - They stay away from the coast, so the sea doesn't flood in.
  - Volcanic caves can have lava at the bottom.
- **Ore veins:** clusters of coal, iron, copper, gold, redstone and lapis, and rarely diamond or emerald.
- **Uncommon treasure tier** between common and rare: buried 2 deep, 50–120 Doubloons, iron tools, copper, lanterns, spyglasses and more.

### Changed
- The highest title is now **Conqueror of the Sea** (crown: Crown of the Conqueror).
- Mountains, caves and ore veins are configured under `generation.terrain` in `islands.yml`.
- New island types (such as Plains) are used automatically, even with an older `islands.yml`.
- TODO: the "Haki" idea is reworked as **Tidewill** (Ironwill, Seasight and Stormcrown), with lore, progression and balance notes.

### Existing servers
- Plains, mountains, caves and ore veins work right away.
- The uncommon tier only appears after `/aquatic resetconfig treasure confirm`, because the loot tiers come from your own `treasure.yml`.
- The new title texts only appear after resetting the modules that show them: tournament, items, guide, crews and events. Each reset keeps a backup.

## 1.4.0 (2026-11-12)

### Changed: our own identity
- **Original names:** every name players see is now our own, not taken from the anime.
  - Abyss Fruits: Springcoil, Emberheart, Rimefrost, Thunderveil, Ghostglass, Riftshift, Hollowvoid, Behemoth
  - Abyssal Iron, the Tide Compass and the Echo Conch
  - the swords Tidecleaver, Nightbloom and Leviathan's Fang
  - the Sea Sovereign title and its crown
  - the Tidewatch enemies
  - bounty titles up to Dread Tyrant
  - the Endless Sea
- **Unchanged:** internal item ids, so existing items and fruit powers keep working.
- **Island names** are now generated from our own word lists per island type ("Palmreach Strand", "Cinderpeak Caldera", ...), never used twice.
- **Getting the new names on an existing server:** your config files still contain the old texts. Run `/aquatic resetconfig <module> confirm` per module; a backup of the old file is kept.

### Changed: islands
- **Random island sizes:** small 100–200, medium 200–350, large 350–600 and huge 600–1000 blocks across. Huge ones are rare.
- **Size limit:** admins can still place islands up to 1500 blocks across by hand.
- **Water colour:** islands only change the biome above land, so the water around them keeps its ocean colour. This removes the square patches of different-coloured water.

### Fixed
- **`/island admin setspawn` set nothing:** it now sets the spawn **exactly where you stand**, including the direction you face, and also sets the world spawn. New players and players without a bed spawn there. Settings are under `spawn:` in `islands.yml`.

### Added
- **Help pages:** every command help is paged. Hovering a line shows the full command, a description and the permission; clicking puts it in your chat bar.
- **`/aquatic commands`:** an overview of all commands, clickable.
- **`/aquatic resetconfig <module>`:** a fresh config with the newest defaults, with a backup of the old one.
- **Chat hover cards:**
  - Hovering a name or crew tag shows crew, rank, reputation, bounty and title.
  - Clicking the tag opens `/crew info`, clicking the name starts a `/msg`.
- **Rank icons** (★ Captain, ☆ First Mate, ⚓ Navigator, ⚖ Quartermaster) in chat and the tab list.
- **Tab list header and footer** with the server name, number online, your crew, Doubloons and bounty. Turn it off with `tablist.header-footer`.

## 1.3.0 (2026-11-12)

### Islands: from small to huge
- **Huge islands:** islands can now have a radius up to **1500 blocks** (`generation.radius-limit`).
  - Generation works **chunk by chunk**: each chunk is loaded in the background, built and released again. Memory use stays small, even for an island 2000 blocks across.
  - Before, every chunk of the island was loaded at the same time, which would crash a server at radius 1000.
  - Restoring the ocean (`delete … restore`, `regen`) streams the same way.
- **Size classes:** `/island admin spawnrandom <type> <count>` without a radius now picks a size by weight:
  - small 100–180
  - medium 180–350
  - large 350–600
  - huge 600–1000
  - Set them under `generation.island-sizes`.
- **Bigger islands are taller:** height grows with size, up to 3× (`generation.height-scaling`, `generation.max-height-boost`).
- **Plains and hills:** islands with a radius of 150 or more mix wide flat plains (room to build) with hilly and mountainous areas.
- **New defaults:** the ocean area is 12,000 blocks and the minimum spacing between islands is 200.
  - Existing servers: run `/island admin area <radius>` to enlarge your sea, otherwise big islands won't fit.
  - The old keys `max-radius`, `random-min-radius` and `random-max-radius` are no longer used; the new keys apply automatically.

### Better ability visuals
- Every ability got real effects:
  - **punch:** beam, impact explosion and shockwave
  - **nova:** expanding frost ring
  - **lightning:** jagged bolt and spark wave
  - **fireball:** muzzle flash and fiery impact ring
  - **pull:** imploding ring
  - **transform:** spiral aura
  - **slash:** crescent arcs
  - **dash:** wind trail
  - **invisibility:** smoke cloud
  - **swap:** beam between the two spots
- **Projectile trails:** fireballs leave a fire trail, cannonballs a smoke trail, bullets a dust trail.
- **Colours:** every ability can get its own colour with `color: "#ff6a00"` in `items.yml`.
- **Effects quality:** `effects.quality` in `items.yml`: 0 = off, 0.5 = light, 1 = normal, 1.5 = rich.

### Fixed
- The "no free ocean spot" message could appear twice.
- **Generation could get stuck:** a chunk was edited while the chunks next to it were still generating, which made Minecraft's lighting step wait forever. A chunk is now only edited when all of its neighbours are fully loaded.

## 1.2.0 (2026-11-12)

### Added
- **Combat module** (new `combat` module, settings in `combat.yml`):
  - **Combat tag:** hitting or being hit by a player puts both in combat for 15 seconds, with a red countdown bar.
    - Escape commands are blocked: `/crew home`, `/spawn`, `/home`, `/tpa`, `/warp`, and teleporting by command.
    - Logging out in combat kills you: items drop, and your attacker gets the kill and any bounty.
  - **Respawn protection:** 10 seconds of PvP immunity after respawning, so nobody can be spawn-camped.
  - **New-player protection:** no PvP during the first 30 minutes of playtime. `/pvp on` ends it early.
  - **`/pvp`** shows your status. Staff: `/pvp untag`, `/pvp protect`, and the bypass permission `aquatic.combat.bypass`.
  - Tournament matches are not affected.
- **Starter kit:** brand-new players get a boat, food, a stone shovel and sword, a Log Pose, 100 Doubloons, and a treasure map to a real buried treasure. Staff can give it out with `/guide kit <player>`.
- **Tips:** new players (under 5 hours of playtime) get a helpful tip in chat every 10 minutes. `/guide tips off` turns them off.
- **Intro:** now also mentions new-player PvP protection.
- `TournamentService.isInMatch` was added to the API.

### Fixed
- **`/island admin cancel` didn't stop a running `spawnrandom`:** it kept generating and reported "0 jobs". Cancel now stops the run too, and reports what it stopped.
- **Generation progress:** now shows as "Island 3/25: Name at x, z" instead of the confusing "(0 job(s) left)".

## 1.1.0 (2026-11-12)

### Added
- **New-player intro** (new `guide` module):
  - On a player's first join, a short series of titles and chat tips explains crews, claiming islands, flags, digging up treasure, bounties, Devil Fruits and the Pirate King. The guide book opens at the end.
  - Players can skip it with `/guide skip`.
  - Everything is in `guide.yml`: steps, texts, timing, and first join vs. every join.
- **`/guide` book:** an in-game written book with 7 chapters, which can also be opened one chapter at a time (`/guide treasure`).
  - Staff: `/guide play <player>` and `/guide reset <player|all>`.
- **Crew flag designer:**
  - `/crew flag set`: the banner in your hand becomes the flag.
  - `/crew flag color <dye>`, `/crew flag preset <name>` and `/crew flag presets`.
  - `/crew flag get`: a copy of the flag banner.
  - `/crew flag reset`: back to the default flag.
  - 7 built-in designs. Add your own under `flag.presets` in `crews.yml`.
  - Optional costs: `costs.flag` and `costs.flag-copy`.
- **Default crew flag:** new crews fly a banner in their crew colour instead of a plain white one. It follows `/crew color` until the crew designs its own flag.
- **Treasure can be dug up anywhere:** anyone may dig straight down to a buried treasure chest and open it, even on claimed or neutral islands. Only the blocks directly above the chest are affected. Turn this off with `treasure-digging: false` in `islands.yml`.

### Changed
- **Planted flags update automatically:** when a crew changes its flag, the banner on every island it owns is updated. Islands that aren't loaded update the next time someone comes near.
- **New config sections are added automatically:** when a newer version adds a section that your existing config file doesn't have yet (such as `flag:` in `crews.yml`), the defaults now apply. Your files are not rewritten.
- `CrewFlagChangeEvent` and `TreasureService.isTreasureDigSpot` were added to the API.

## 1.0.4 (2026-11-12)

### Fixed
- **Island enter/leave messages only showed the first time:** while islands were generating, the progress line kept overwriting the action bar of whoever started it, every half second. Enter and leave messages use that same action bar, so they vanished. Generation progress is now a separate boss bar at the top of the screen.

### Changed
- **Enter/leave display:** entering or leaving an island now shows a short title by default, every time.
- **New `presence:` settings in `islands.yml`:**
  - `enter-display` and `leave-display`: `title`, `actionbar`, `both`, `chat` or `none`.
  - `repeat-cooldown-seconds` (default 5): the same message isn't repeated when you sail along a coastline.
  - Title timing: `title-fade-in-ticks`, `title-stay-ticks`, `title-fade-out-ticks`.
- The old `presence.display` key is no longer used. Servers that still have it get the new title display automatically.

## 1.0.3 (2026-11-12)

### Changed
- **Island admin commands:** these are now listed only under `/island admin`, so admins no longer see every command twice in help and tab completion. The short form, such as `/island spawn`, still works.
- **`/island list`:** now shows each island's coordinates. Admins also get a clickable **[TP]** button behind every island.
  - Existing servers keep the old line format, because the message is already saved in their `islands.yml`.
  - To get coordinates there, delete the `list-entry:` line from the `messages:` section of `islands.yml`, then run `/aquatic reload islands`.

## 1.0.2 (2026-11-12)

### Added
- **Credits:** "Developed by tombomeke" now appears in the startup banner in the server console and in `plugin.yml` (shown by `/version AquaticSMP` and `/plugins`).
- **`/aquatic about`** (aliases `credits`, `version`): shows the version, the author and the project link. It is open to every player. Running `/aquatic` with no arguments shows the same card, followed by the admin help for staff. The admin sub-commands still need `aquatic.admin`.

## 1.0.1 (2026-11-12)

Bug-fix release. Tested on a Paper 1.21.4 server from the console: startup, reload, toggling modules, island generation, the Lost Island event and shutdown. Features that need a player in the game have not been tested yet.

### Fixed
- **Commands after a reload:** disabling or reloading a module did not remove its commands, so `/crew` and others could keep running the old copies. Commands are now cleanly removed and registered again.
- **Re-enabling a module:** modules that were switched off only because a module they depend on went off now come back when that module is turned on again. Example: `/aquatic enable crews` brings `tournament` back.
- **Lag spike from random islands:** `/island spawnrandom` no longer freezes the server for several seconds while it looks for free ocean. Spots are now checked in the background, and a few are prepared ahead of time so the Lost Island event starts instantly. New settings in `islands.yml`: `ocean-area.prefetch-spots` and `ocean-area.prefetch-radius`.
- **Bounty Hunt leftovers:** the event's extra bounties are now removed from targets nobody claimed. Turn this off with `remove-unclaimed-bounties` in `events.yml`.
- **Treasure loot:** chests now contain the Log Pose and random Devil Fruits; before, those entries used item names that didn't exist and were skipped. `devil_fruit_random` is a new item id that gives a random Devil Fruit.
- **Devil Fruit and sword abilities:**
  - A dash that killed a player could crash the task that runs abilities every tick.
  - When one ability hit set off another, the first could be wrongly counted as blocked.
- **Slow memory growth:** each module kept every finished delayed task in a list forever. Finished tasks are now cleared.
- **Server hang:** `/island discovered <player>` could freeze the server while looking up an unknown player name online. It now only uses players the server has seen before.
- **Pirate King crown in chat:** the crown prefix could show stale information.

### Documentation
- Added `COMMANDS.md`: every command and its permission, plus how to run alongside EssentialsX, TAB and chat plugins.
- Added a Configuration section to `README.md`.

## 1.0.0 (2026-11-12)

First build: economy, crews, bounty, islands, treasure, items, tournament and events modules.
