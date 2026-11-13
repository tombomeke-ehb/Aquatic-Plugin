# Changelog

## 2.18.1 (2026-11-13)

### Added
- **Effects on the last items that had none:**
  - **Coral Trident:** a slash on a charged hit, a crescent and a glowing, bubbling trail when thrown, and a splash burst where it lands.
  - **Deepcore Pickaxe:** a slash on a charged hit, plus sparks and light on every block it breaks (a bigger burst on ores).
  - **Marauder's Buckler:** a flash, sparks and a ring when it blocks a hit.
  - Any custom tool or weapon gets the charged-hit slash.
- **Stronger fruit moves:**
  - **Fireballs:** a flash and crescent on launch, a glowing trail, and a nova plus a pillar of fire on impact.
  - **Dashes:** a shockwave and flash at the start, and an afterimage streak.
- **Hand Cannon:** a nova and a shockwave on impact.

### Changed
- **Less cartoony armour.**
  - **Tidewarden plate:** blued steel with brass trim instead of bright blue and neon. It has brushed metal, scratches, pitting, grime under the plates, a quilted gambeson and an etched emblem.
  - **Coats, hats, breeches and boots:** these are repainted at 4x with a wool twill weave, folds and worn edges.
    - The coats have facings, brass buttons and a leather belt.
    - The epaulettes have fringe.
    - The felt hats have a ribbon band, braid and a cockade.
    - The boots are leather with a turned-down cuff.
  - **Dyes:** all sets use muted, period colours: madder red, navy, buff and aged gold.
  - **Icons:** they use the same colours and lose the hard black outline.

## 2.18.0 (2026-11-13)

### Added
- **New Tidewatch forts, three variants** (picked per island, or set `types.TIDEWATCH.fort.variant` in `islands.yml`):
  - **The star fort:** stone curtain walls with a wall walk and crenellations, four diamond bastions with cannons and braziers, a gatehouse with a raised portcullis, barracks, training dummies and supplies.
  - **The coastal battery:** a low wall, a raised gun deck with five cannons in embrasures, a brick powder magazine, a tall watchtower with a signal fire, and barracks.
  - **The stockade:** a pointed log palisade with a walk behind it, four roofed watchtowers on stilts, a timber blockhouse with a steep roof, tents and a camp.
  - Every fort has a three-storey keep in the middle (captain's hall, bunks, a map room, a roof with the Tidewatch flag). The Warden Captain waits on its ground floor.
- **New effects:**
  - A pillar of light, an orbit aura, a nova burst, rising sparkles, sound-wave rings and chains.
  - **Eating a fruit:** a pillar of light, a nova and an aura in the fruit's colour.
  - **Every fruit move:** a flash and sparks at the hand.
  - **Drinks:** a swirl in the drink's colour and sparkles, plus hearts when they heal.
  - **Bombs:** a nova and a flash on top of their cloud.
  - **The Echo Conch:** sound-wave rings, a pillar, and a light above every crewmate who hears it.
  - **The Tide Compass:** a short trail of light pointing to the island.
  - **The Seafoam Milkshake:** a white burst and a fading glow.
  - **Abyssal Shackles:** chains to the cuffed player and an aura around them.
  - **Armour sets:** an orbit aura every 10 seconds.
  - **The Conqueror's crown:** golden sparks above the head.

### Changed
- **Better textures:** every armour and clothing icon is redrawn.
  - Coats have lapels, double rows of buttons, a belt, cuffs and split tails.
  - Plate has pauldrons, a ridge, belly plates and a gem; the helmet has a visor and cheek guards.
  - Boots and hats are shaped.
  - Every item icon also gets a lighting pass: a lit top-left edge and a shaded bottom-right edge.
- **Tidewatch soldiers** only appear on open courtyard ground (never on roofs, walls or towers).

### Fixed
- **The fort's strongbox** is buried in the open yard in front of the keep. It no longer ends up under a roof, sticking out of a floor, or near a crew flag.

### Your server
- Configs upgrade automatically. Existing outposts keep their old fort; new ones (or `/island admin regen <island>`) get the new forts.
- The resource pack has changed; the plugin sends it by itself.

## 2.17.0 (2026-11-13)

### Added
- **The gambling den is built at spawn:** every new capital gets the striped den on its plaza, in the spot where one of the small outer market stalls used to stand.
  - The Dealer stands behind its counter.
  - The Sailor's, Captain's and Kraken's crates sit on its pedestals and become crates by themselves (no `/crate set` needed).
- **Crates register themselves:** crate blocks near a capital's centre become crates automatically (`auto-register` in `crates.yml`). `/crate setup` finds them first, and only places new stands when there are none.
- **Beaches are no longer empty:** parasols with towels, driftwood, washed-up cargo, upturned rowboats and campfires.
- **Weapon effects everywhere:**
  - Every fully charged hit with a custom weapon leaves a glowing slash arc in the weapon's colour, with a flash on the target.
  - On-hit procs add a shockwave.
  - Every weapon ability starts with a crescent swing.

### Changed
- **Fruit moves grow with mastery:** range, radius and width grow 5% per mastery level (was 2%), so a level 20 move is almost twice as big.
- **No enchant glint over animated textures:** fruits and other items with their own texture no longer glow, so their animation is visible. Without the resource pack they still glint.
- **Piers are wider:** a 3-block walkway between the railings, and 5 at the end. The harbour bell hangs from a post and arm now, not on a fence.
- **Tidewarden armour redrawn** as proper plate armour at 4x resolution: a crested helmet with a face opening, a breastplate with a ridge, belly plates and a glowing emblem, layered pauldrons, bracers, knee guards, greaves and sabatons.
- **Guns in third person** are held like a crossbow, pointing forward (they were held sideways).
- **Sidebar:** the level is shown next to the XP bar, so the sidebar is narrower.

### Fixed
- **Doors:** window shutters and flower boxes are never placed in front of a door any more (some houses couldn't be entered).
- **Windows:** glass panes join the walls they sit in.
- **Crates:** they no longer leave see-through holes in the blocks next to and under them (the chest now fills its whole block footprint).
- **The lighthouse** always finds a spot near a road end again.
- **Villagers:** zombies and other mobs no longer go after the spawn villagers and the village quest givers.
- **Fort strongbox:** the treasure from a liberated Tidewatch fort is buried next to the crew flag, not on top of it.

### Your server
- Configs upgrade automatically, with backups.
- Existing capitals don't get the den by themselves. Paste one with `/island admin schematic preview builtin:gambling_den`; its crates register themselves. Then move the Dealer in with `/hubadmin place dealer`, or regenerate the capital.
- The resource pack has changed; the plugin sends it by itself.

## 2.16.3 (2026-11-13)

### Changed
- **No more big illager noses:** the Tidewatch's nose is invisible now, with a small, normal nose painted on the face instead. The old painted hat is short hair now, because the hats are real 3D models (below).
- **Every soldier looks different:** each one gets a random look when it spawns, a 3D hat with or without facial hair.
  - **Riflemen:** a tall shako with a badge and pompom, sometimes with a brown, black or ginger mustache, a beard or an eyepatch.
  - **Lieutenants:** a tricorn with gold edging, sometimes with a grey mustache, a black or ginger beard, or an eyepatch.
  - **Warden Captains:** a wide bicorne with a cockade and a white plume, with a white mustache, a grey beard or an eyepatch.
- The looks are lists under `tidewatch.mobs.<soldier>.equipment.helmet` in `islands.yml` (`look:<id>`). Remove entries to make one more or less common, or repeat one to make it appear more often.

### Your server
- `islands.yml` upgrades automatically (only soldiers whose helmet was never changed get the new looks). The resource pack has changed; the plugin sends it by itself.

## 2.16.2 (2026-11-13)

### Changed
- **The Tidewatch look sharper** (resource pack): the Riflemen (pillagers) and the Lieutenants and Warden Captains (vindicators) have new 128x128 skins, twice the detail.
  - A tricorn with a trim band, and a shaded face with a heavy brow, eyes and stubble.
  - A woven uniform coat with crossed belts and a buckle, double rows of buttons, a collar, epaulettes with fringe and cuffs.
  - A trimmed coat hem with split tails, white trousers with creases, and polished boots.
  - Light from above: tops are brighter and sides darker, so they look rounded.
  - Officers wear dark navy and gold; riflemen wear blue and white.

### Your server
- Only the resource pack changed. Swap the jar; the plugin sends the new pack by itself.

## 2.16.1 (2026-11-13)

### Changed
- **The crates are now 3D treasure chests** (resource pack):
  - A wooden body with plank seams, grain, knots and nails.
  - A rounded lid, two metal bands over the lid and body, metal corner guards, a rim, little feet, and a lock plate with a gem keyhole on the front and the back. The gem slowly pulses.
  - Higher-resolution textures (64x64) in each crate's colours: the Sailor's Crate in spruce and iron with a cyan gem, the Captain's Crate in dark oak and gold with a ruby, and the Kraken's Hoard in deep purple wood with glowing teal metal and a violet gem.
  - They look right as a held item and in the inventory too.

### Your server
- Only the resource pack changed. Swap the jar; the plugin sends the new pack by itself.

## 2.16.0 (2026-11-13)

### Added
- **Crate blocks:** every crate has its own block, nether blocks nobody can get with the Nether off.
  - The Sailor's Crate is Nether Quartz Ore, the Captain's Crate is Nether Gold Ore, and the Kraken's Hoard is Shroomlight (it glows).
  - The resource pack turns them into 3D crates with corner posts, a lid, metal bands and a gem lock, in each crate's colours.
  - `/crate item <crate>` gives staff the block: place it anywhere and it is a crate. `/crate setup` uses these blocks too.
- **The crew menu runs the whole crew** (`/crew`), by clicking:
  - Member pages: promote, demote, any rank, make captain, kick.
  - A bank page with 100 / 1,000 / 10,000 / everything, and inviting from a list of players.
  - Diplomacy with every crew (alliance, neutral, rivals, war, peace).
  - The flag designer, friendly fire, crew home (right-click sets it), and leaving or disbanding.
  - A confirmation page for anything that can't be undone. Without a crew: browse all crews and click an invite to join.
- **MEGA legendary treasure:**
  - 14–18 items instead of 7–9, plus 2–3 guaranteed jackpot items (Kraken keys, fruits, netherite, diamond blocks).
  - 15,000–25,000 Doubloons.
  - The new weapons in the loot.
  - A light beam over the chest, and a firework show, a title and a fanfare for everyone when it's found.

### Changed
- **The shop and the gambling den are only reached through the villagers.** Players have no `/shop`, `/gamble`, `/dice`, `/wheel` or `/coinflip` commands any more.
  - The shopkeepers open their market pages directly (the Blacksmith's tools button too), and the Dealer opens the den.
  - Coin flips are started in the Dealer's menu: pick a player and a bet. They accept or turn it down at the Dealer.
  - Staff keep `/shop` and `/gamble`.
- **A prettier town** (new capitals and villages):
  - **Houses:** a stone plinth, timber beams, shutters by every window, flower boxes under the ground-floor windows, a door awning with two lanterns, and two new house styles.
  - **Piers:** log-edged decks with mixed planks, continuous railings, lantern posts, and a wide end with cargo, a bell and a ladder down to the water.
  - **The parkour tower:** a weathered pillar with sea-lantern rings, jumps in prismarine and quartz, a railed platform on top, and lamp posts at the start.
  - **The lighthouse:** the tall striped octagonal lighthouse with a gallery and a lantern room, its door facing the town.
  - The market stalls are unchanged.

### Your server
- Configs upgrade automatically, with backups: `crates.yml`, `treasure.yml` (if the legendary tier was never edited), `hub.yml` (the villagers open the menus directly), `gambling.yml` and `crews.yml`.
- Existing towns are not rebuilt. New capitals and villages get the new buildings (`/island admin regen <island>` rebuilds one).
- The resource pack has changed (the crate blocks). The plugin sends the new one by itself.

## 2.15.0 (2026-11-13)

### Added
- **Crates:** three crates with their own keys (new key textures in the resource pack): the Sailor's Crate, the Captain's Crate and the Kraken's Hoard.
  - Right-click with a key and a reel of prizes spins, slows down and stops on your prize. Closing it early still gives the prize.
  - Left-click (or right-click without a key, or `/crate`) shows every prize and its chance.
  - A floating sign and particles above each crate, and big wins are announced.
  - **Works with your builds:** any block can be a crate. Paste a schematic, look at its chest and use `/crate set <crate>`. `/crate setup` places three default stands at the capital you stand on.
  - **Keys** turn up in treasure (uncommon and up), can be given with `/crate give` / `giveall`, and work in every reward list as `key:<crate>:<amount>`.
- **The gambling den** (Doubloons only, at the capital), with a **Dealer** villager at spawn and `/gamble`:
  - **Dice** against the dealer: higher wins double, a tie gives half back.
  - **Wheel of fortune:** a multiplier from nothing to x10. It pays back about 93% on average.
  - **Coin flips** between players. The house keeps 5%.
  - **Fair limits:** level 5 or higher, bets from 10 to 5,000, 25,000 a day, a short cooldown, and a log of big results in `data/gambling.log`.
  - A built-in **den building** to paste: `/island admin schematic preview builtin:gambling_den`. It's a striped tent with a counter, a dice table and crate pedestals. Every built-in facility can be pasted this way too (`builtin:lighthouse`, ...).
- **`/treasure loot add <tier> [weight]`:** add the item in your hand to a treasure tier, keeping custom items, crate keys and enchantments. `/treasure loot list` and `remove` see and remove entries.
- Treasure loot entries accept `item: "key:sailor:1"` (any reward spec).

### Changed
- `/hallofkings` is now `/hallofconquerors`. The old name still works.

### Code
- One parser for reward items everywhere (quests, level rewards, crates, treasure) and one shared menu-icon helper, replacing several copies.

### Your server
- New modules `crates` and `gambling` (on by default; switch off in `config.yml`). New files `crates.yml` and `gambling.yml`.
- `hub.yml` (the Dealer), `treasure.yml` (crate keys in the loot) and `tournament.yml` upgrade automatically, with backups.
- The resource pack has changed (key textures). The plugin sends the new one by itself.

## 2.14.0 (2026-11-13)

### Added
- **Your own builds from schematic files.** Put them in `plugins/AquaticSMP/schematics`:
  - `.schem` files from WorldEdit or FastAsyncWorldEdit (versions 2 and 3), or `.nbt` files from a structure block.
  - Save a build with its entrance on the north side, or say which side it was on with `front`.
- **Paste builds by hand** (for the spawn town):
  - `/island admin schematic preview <file>` shows a ghost of the build in front of you that only you can see.
  - Then [Paste] (it rises with the build animation), [Paste now], [Rotate], [Move here] or [Cancel].
  - [Undo] puts back everything your last paste replaced, chest contents included.
- **Automatic builds per island type:** list schematics under `builds` in `islands.yml`.
  - For example `CAPITAL: [{schematic: town_hall.schem, offset: [0, 30], facing: north}]`.
  - They are placed every time such an island is generated. `/island admin builds <island>` adds them to an existing island.
- **Facilities from schematics:** give a facility type a `schematic` in `islands.yml`. It can replace a built-in design or add a completely new facility with its own price, limit and income bonus. Crews place it with the usual preview and animation.
- The facilities menu now has room for 21 facilities.

### Not yet
- Only blocks are copied: chest contents, sign text, banners and entities (item frames, armour stands) are not copied yet.

### Your server
- `islands.yml` upgrades automatically (a backup is kept): new `schematics` and `builds` sections.
- The `plugins/AquaticSMP/schematics` folder is created on start, with a short README.
- The resource pack is unchanged.

## 2.13.0 (2026-11-13)

### Added
- **Facilities:** crews can build real buildings on their islands, paid from the crew bank (Captain and First Mate). There are six:
  - **Watchtower:** a stone base, a timber shaft with windows, a lookout platform with a bell, and a hipped roof.
  - **Forge:** an open smithy with an anvil, smithing table, blast furnace, lava, grindstone, storage and a smoking chimney.
  - **Farm Field:** a fenced field with ripe crops, a water channel with lily pads, and a scarecrow.
  - **Dock:** a dock out over the water on posts, with railings, lanterns and cargo.
  - **Storehouse:** a timber-framed storehouse with barrels and chests, flower boxes and a chimney.
  - **Lighthouse:** red and white, with a gallery and a glowing lantern room.
- **Preview first:** pick a facility in `/island manage` (Facilities) and a ghost of it appears in front of you that only you can see.
  - Its outline on the ground turns green when it fits and red when it doesn't.
  - Your own blocks in the way show as red glass, and chat says what is wrong.
  - Click [Rotate], [Move here], [Build] or [Cancel].
- **Built with an animation:** the site is cleared, then the building rises from the ground with blocks flying into place, dust and building sounds.
  - Crewmates nearby see a progress bar, and there's a celebration when it's done.
  - It takes about 30 seconds (the Lighthouse 45).
- Most facilities raise the island's income: +5% each, the Dock +10%.
- Demolish one by standing in it and typing `/island facility remove`. Half the price comes back, chest contents drop and the ground goes back to grass.
- Everything (prices, limits per island, build time, the animation) is under `facilities` in `islands.yml`.

### Your server
- `islands.yml` upgrades automatically (a backup is kept). The resource pack is unchanged, so players don't download it again.

## 2.12.1 (2026-11-13)

### Changed
- **The plugin sends the resource pack itself.** No more `server.properties` lines to update:
  - Every jar knows its own pack (the download link on its GitHub release and the SHA-1), so updating the jar updates the pack.
  - Players only download it again when the pack really changed.
  - New settings under `resource-pack` in `items.yml`: `send`, `required` (decline = disconnected), the `prompt` text, and `url` + `sha1` to send your own pack instead.

### Your server
- **Once:** remove the `resource-pack=` and `resource-pack-sha1=` lines from `server.properties` (leave them empty), or players get the old pack as well. The server log warns you while they are still there.
- `items.yml` upgrades automatically (a backup is kept). `resource-pack.enabled` must be `true`, as before.

## 2.12.0 (2026-11-13)

### Added
- **Village islands:** a new neutral island type that nobody can claim, build on or fight on.
  - Cottages, a smithy (anvil, blast furnace, smithing table, grindstone), two fishing huts, a tavern and fenced farm fields with crops along dirt roads to the coast.
  - A well in the middle of the village square, and piers at the ends of the roads.
  - Villages show up in random island spawns (about as often as Tidewatch outposts), or make one with `/island admin spawn village <radius>`.
- **Quests:** four villagers stand around every village well: the Village Elder, the Old Fisher, the Village Smith and the Hunter.
  - Right-click one to see their quests, then click [Accept] and later [Turn in].
  - Catch fish, hunt mobs or bring materials, for Doubloons, pirate XP and sometimes items.
  - Quests repeat after a cooldown. You can do 3 at once. `/quests` shows your progress.
  - Everything is set in the new `quests.yml`.

### Changed
- **The shop is only at the shopkeepers:** `/shop` no longer opens the market for players. They talk to the shopkeeper villagers at spawn instead (the Blacksmith's tools button still works).
  - Ops can still use `/shop`. Turn the command back on for everyone with `command.players-can-open: true` in `shop.yml`.
- The guide book's market page now tells players to visit the shopkeepers.

### Your server
- Configs upgrade automatically, with backups kept (`islands.yml`, `shop.yml`, `hub.yml`, `guide.yml`).
- Existing islands are not changed. New villages appear with new random island spawns, or spawn some with `/island admin spawnrandom village <count>`.
- Update the resource pack link and SHA-1 in `server.properties` (see the release notes).

## 2.11.0 (2026-11-13)

### Added
- **Four new weapons and tools.** Each one can be crafted and has its own texture:
  - **Whaler's Harpoon:** right-click to **Reel In**, which pulls nearby enemies towards you.
  - **Coral Scimitar:** a curved coral blade. Right-click sends a **Reef Wave** across the ground.
  - **Abyssal Anchor:** a heavy, slow weapon (10 damage). Sneak + right-click for **Anchor Drop**, a slam that knocks everyone back.
  - **Deepcore Pickaxe:** Efficiency V and Fortune III, never breaks, and has a glowing gem in its head.
- **Animated weapons:** a shine sweeps along every sword, club, hammer and tool. Runes and gems pulse (Storm Saber, Abyssal Baton, Tidecleaver, Nightbloom, Leviathan's Fang, Tide Rapier, Abyss Cutlass, Kraken's Maul and the new items).
- **Every fruit is animated now:**
  - Springcoil bounces.
  - Ghostglass fades in and out with ghostly sparkles.
  - Behemoth stomps up dust.
  - Stoneskin glints and has pebbles circling it.

### Changed
- **Fruit powers are rare among Tidewatch officers:** only about 1 in 5 Lieutenants and Warden Captains has one. Change this with `tidewatch.power-chance` in `islands.yml`, or set `power-chance` on a single officer type.

### Your server
- `items.yml` upgrades automatically and adds the new items (a backup is kept).
- Update the resource pack link and SHA-1 in `server.properties` (see the release notes).

## 2.10.0 (2026-11-13)

### Added
- **LuckPerms/Vault ranks in chat and the tab list:**
  - The chat line is now `[rank] [level] [crew] name [logo] » message`. The rank is your LuckPerms prefix and the logo your LuckPerms suffix.
  - The tab list shows the same for everyone (players without a crew too) and refreshes every 30 seconds.
  - Colour codes (`&c`, `&#ff8800`) and MiniMessage both work.
  - The new tags are `<rank>` and `<suffix>` in `crews.yml`.
- **The Musket:** a long gun with its own 3D model.
  - 15 damage (double on a headshot), flies straight and far, takes 3.5 s to reload and uses 2 gunpowder per shot.
  - Craft it or buy it in the Pirate Armory (4,500 Doubloons).
- **Fruits preview their power:**
  - **Animated textures:** every fruit's swirl turns, and each one shows its power: Rimefrost glitters with frost, Emberheart's flames flicker, Thunderveil sparks, Luminara pulses, Tidecaller bubbles, Galeborn has wind streaks, Venomfang drips, Dunemaw trickles sand, and Hollowvoid and Riftshift have wisps around them.
  - **Held particles:** holding a fruit you haven't eaten gives off a few particles of its power. Turn this off with `effects.fruit-preview` in `items.yml`.
- **Gun effects:** a muzzle flash, sparks and smoke when firing, a glowing tracer, and sparks and debris where the shot hits.

### Changed
- **The Tidewatch are illagers in uniform:**
  - **Riflemen** are Pillagers with crossbows.
  - **Lieutenants** and the **Warden Captain** are Vindicators. They move and fight like soldiers and still use their fruit powers.
  - The resource pack redraws both in Tidewatch uniform. This changes how every pillager and vindicator looks, but in an ocean world the only ones are the Tidewatch and the Grand Raid's.

### Your server
- Configs upgrade automatically, with backups kept.
- Update the resource pack link and SHA-1 in `server.properties` (see the release notes).

## 2.9.1 (2026-11-13)

### Changed
- **Better effects for fruits and weapons.**
- **Animated 3D effects (with the resource pack):** glowing effect models that grow and sweep for a few ticks, in the colour of each move.
  - **Slashes:** a crescent that sweeps forward.
  - **Shockwaves** (Gale Burst, Geyser, Tidal Slam, ...): a ring that expands over the ground.
  - **Impacts and lightning:** a bright star-burst flash.
- **Better particles (with or without the pack):**
  - Energy dust fades from a bright shade to the move's colour.
  - Beams have a glowing core with two strands spiralling around it.
  - Shockwaves kick up debris from the ground they hit.
  - Lightning has side branches and sparks where it lands.
- **Settings:** `effects.animated` in `items.yml` turns the 3D effects off. `effects.quality` still scales all particles.
- **Your server:** update the resource pack link and SHA-1 in `server.properties` (see the release notes).

## 2.9.0 (2026-11-13)

### Added
- **Four new Abyss Fruits** (with textures; they also appear in treasure):
  - **Tidecaller:** Riptide (a crashing wave), Whirlpool (pulls enemies in) and Geyser (a blast upward).
  - **Venomfang:** Fang Strike (a poisonous lunge), Venom Spray and Coil (slows). Makes you faster.
  - **Dunemaw:** Sand Lash, Dust Veil (dash and turn invisible) and Quicksand.
  - **Luminara:** Prism Beam (piercing), Flashpoint (blinds everyone around you) and Lightstep. Gives night vision.
- **Tidewatch uniforms:** the garrison wears their own uniforms, which get their own look with the resource pack. Without the pack they show as dyed navy leather.
  - **Soldiers:** a navy bicorne and coat, white trousers and black boots.
  - **Lieutenants:** a gold-trimmed officer's coat.
  - **The Warden Captain:** a white and gold uniform.
  - The uniforms don't drop and aren't for sale.
- **Officers use fruit powers:**
  - **Tidewatch Lieutenants** use Rimefrost or Galeborn.
  - **The Warden Captain** gets one of four at random: Emberheart fireballs, a Thunderveil lightning strike, a Rimefrost freezing nova or a Galeborn gust. Captains hit 40% harder.
  - Their power shows in their name, and every attack comes with a one-second warning (particles and a sound) so you can dodge.

### Changed
- **Lieutenants and the Warden Captain are now uniformed soldiers** (zombie-type) instead of vindicators, so the uniform shows. They don't burn in daylight and don't turn into drowned.

### Your server
- The new items are added to `items.yml` automatically.
- Update the resource pack link and SHA-1 in `server.properties` (see the release notes).

## 2.8.0 (2026-11-13)

### Added
- **3D models:** the **Flintlock** and the new **Hand Cannon** are real 3D models, so they're no longer flat.
  - Flintlock: a barrel, a wooden stock, an angled grip with a brass butt cap, lock plates, the cock and a trigger guard.
  - Hand Cannon: a heavy barrel with brass bands, a flared muzzle and a fuse.
- **Custom textures for 40 of the 43 custom items:**
  - all 10 Abyss Fruits
  - every sword: Tidecleaver, Nightbloom, Leviathan's Fang, Tide Rapier, Abyss Cutlass, plus the Kraken's Maul
  - the Abyssal Iron Shackles
  - Smoke, Frost and Fire Bombs, and the cannonball
  - the Echo Conch
  - Pirate Rum, Healing Tonic, Grog of the Gale and the Seafoam Milkshake
- **Armour that looks different when worn:** the Tidewarden set (blue steel), the Corsair set (red coat, breeches, black tricorn and boots) and the Captain's Greatcoat have their own icons and their own look on your body.
- **Still the normal look**, because a custom model would break how they work:
  - the **Tide Compass** (its needle)
  - the **Marauder's Buckler** (the shield blocking pose)
  - the **Coral Trident** (the throwing pose)
- **Your server:** the upgrade adds the new texture lines automatically. Update the resource pack link and SHA-1 in `server.properties` (see the release notes).

## 2.7.5 (2026-11-13)

### Changed
- **Every texture redrawn at higher quality:** 32x32 instead of 16x16, with real shading (light from the top-left), shine and clean outlines.
  - **Crown of the Conqueror:** five spikes with gold balls on top, a rounded band with rivets, a cut ruby and two sapphires. The crown you see on your head is sharper too.
  - **Stormcaller Saber:** a shaded steel-blue blade with a jagged lightning bolt along it, a gold crossguard and a wrapped grip with a gem pommel.
  - **Abyssal Iron Baton:** now a heavy club (no longer a recoloured sword) with iron bands and glowing runes.
  - **Flintlock:** a polished barrel, wood all along under it, a curved grip with grain, and a brass trigger guard and butt cap.
  - **Fruits:** round shaded fruits with a bold abyss swirl, a curved stem and a veined leaf.
- **Your server:** update the resource pack link and SHA-1 in `server.properties` (see the release notes).

## 2.7.4 (2026-11-13)

### Fixed
- **Flintlock texture:** it was the wrong way round in the hand; it now points forward. Update the resource pack link and SHA-1 in `server.properties` (see the release notes).

## 2.7.3 (2026-11-13)

### Fixed
- **Tide Compass:** it keeps the normal compass look again, so its needle points the way again. A still custom texture removed the needle animation.
  - Existing servers get this automatically, and Tide Compasses players already own switch back when they log in or open a chest.
- **Flintlock:** redrawn as one solid pistol (barrel, wooden stock and grip, brass hammer and trigger guard). The old texture looked broken.
- **Your server:** update the resource pack link and SHA-1 in `server.properties` (see the release notes).

## 2.7.2 (2026-11-13)

### Added
- **More textures in the resource pack:**
  - **Abyss Fruits:** Springcoil (pink), Emberheart (orange), Rimefrost (icy blue) and Thunderveil (yellow with a purple swirl).
  - **Flintlock:** a pistol with a wooden grip and brass details.
  - **Tide Compass:** a brass compass with a sea-blue face.
  - **Abyssal Iron Baton:** dark sea-green metal.
- **Your server:** existing servers get the new `item-model` lines automatically. Update the resource pack link and SHA-1 in `server.properties` (see the release notes).

## 2.7.1 (2026-11-13)

### Added
- **Custom textures (resource pack):** the first two items have their own look.
  - The **Crown of the Conqueror** has its own icon and is a real crown when worn: a gold band with spikes and gems.
  - The **Stormcaller Saber** has an icy blue blade with a lightning stripe and a gold guard.
- **Getting the pack:** `AquaticSMP-ResourcePack.zip` is attached to this release. The sources and the build script are in `resourcepack/` (`python resourcepack/build_pack.py`).
- **Switching it on:** `resource-pack.enabled` in `items.yml` turns the custom looks on. Leave it off until your server sends the pack; turning it off again restores the normal look, also on items players already own.

### Fixed
- **Every door can be reached:** houses on a raised foundation (sloped ground) now get a landing and stone steps down from the door.
- **Fewer windows beside the door:** only about 1 in 4 houses have windows right next to the door.

### Changed
- **The outer plaza is no longer empty:** a ring of tree planters, wells and small market stalls, and more crate piles.

## 2.7.0 (2026-11-13)

### Added
- **Seafoam Milkshake:** drinking it washes your Abyss Fruit power away, so you can eat a different fruit.
  - Your mastery of the old fruit is kept, if you ever eat it again.
  - Buy it in the Pirate Armory (3,000 Doubloons) or craft it: milk bucket, sugar, 2 prismarine crystals and a golden apple. The recipe is in `/recipes`.
- **A fountain at spawn**, north of the throne: a round stone basin with glowing prismarine, and water pouring from the top.

### Changed
- **A livelier capital:**
  - **More buildings:** 30 instead of 16, in a second row behind the first along every road.
  - **Three sizes:** small cottages, houses, and big two-storey houses.
  - **A tavern:** a bar with barrels and a brewing stand, tables with chairs, and a lamp by the door.
  - **A warehouse:** stacks of barrels and hay, and a wide doorway.
- **Plaza decoration:**
  - benches facing the throne
  - a ring of lamp posts
  - red and white banner poles at every road entrance
  - flowering azalea hedges along the edge
  - crate piles with flowers
- **Better market stalls:**
  - log posts and a striped awning with a raised middle and a scalloped front edge
  - a counter with goods and a lantern hanging over the shopkeeper
  - a back shelf with goods for the trade (anvil and grindstone, melons and hay, books and a lectern, …)
  - crates on the side and flower barrels out front

### Updating
- Replace the jar and restart. The milkshake is added to your items and shop automatically.
- The new spawn look only appears on a capital generated or regenerated with this version: `/island admin regen <capital>` (this replaces what's on it).

## 2.6.1 (2026-11-13)

### Fixed
- **No more random shallow holes in the grass:** a cave entrance now only opens where a tunnel really continues down into the caves. In a test, an island from the previous version had 19 of these pits and a new one had none, with the same amount of caves.

### Changed
- **Large islands are smaller:** radius 225–375 (450–750 blocks across), was 275–450. Existing servers get this automatically unless you changed the sizes yourself.

## 2.6.0 (2026-11-13)

### Added
- **Smithy** (`/smithy`, or the Blacksmith villager at spawn):
  - Upgrade the item in your hand from +1 to +5:
    - weapons hit harder
    - armour protects more
    - pickaxes, shovels and hoes mine faster
    - bows shoot harder (crossbows load faster)
  - Each level costs Doubloons and materials (iron → gold → diamonds → netherite), with a success chance from 100% down to 30%.
  - A failed attempt costs the materials but never breaks the item.
  - The item gets a "+N" after its name and a "★ Smithed +N" line.
  - Its normal stats are kept.
- **Crew menu:** `/crew` on its own opens a menu.
  - It shows the crew's info, members with their rank and online status, the bank, crew home, island home, diplomacy, crew chat, invites and leaving.
  - Officers can promote, demote or kick members from the menu.
  - Without a crew, it has buttons to found a crew, browse crews and accept invites.
  - `/crew help` still lists the commands.
- **Level rewards:**
  - Every 5 levels gives Doubloons, items and a **title**: Deckhand, Sailor, Bosun, Navigator, Corsair, Sea Wolf, Reaver, Stormcaller, Dread Corsair, Legend of the Sea.
  - Each reward is given once. Players already past a level get it on their next login.
  - `/level rewards` lists them. The title shows in `/level` and next to your level on the sidebar.
- **Island upgrades:**
  - **Vault:** crew storage on the island with `/island vault` or the manage menu (9, 27 or 54 slots). Conquerors take what's inside!
  - **Granary:** crewmates slowly get their hunger back on the island.
- **Tidewatch outposts:** a new island type (TIDEWATCH, rare).
  - **The fort:** walls with a walkway, four towers with banners, and a two-storey keep with a flag.
  - **The garrison:** while players are on the island, Tidewatch Recruits, Riflemen and Lieutenants keep coming. It grows with the number of players.
  - **The Warden Captain** guards the keep. Kill him to **liberate** the island:
    - every attacker on the island gets 1,500 Doubloons and 400 XP
    - the fort's strongbox is buried in the keep (a rare treasure)
    - crews can claim the island for 12 hours
  - Unclaimed forts get a new garrison afterwards. While the Tidewatch holds the island, it can't be claimed.
  - `/island info` shows whether the Tidewatch holds the island.
  - Everything is in `islands.yml` under `tidewatch`.

### Updating
- Replace the jar and restart. Configs upgrade automatically, with backups kept:
  - the new upgrades, the Tidewatch island type and its names are added
  - the Blacksmith opens the smithy
  - the sidebar shows your title
- Generate Tidewatch islands with `/island admin spawnrandom tidewatch <count>`. They also appear among random islands.

## 2.5.1 (2026-11-13)

### Added
- **Treasure maps in the shop** (Sailing category). There are three kinds:
  - **Treasure Map** (common to rare), 400 Doubloons
  - **Epic Treasure Map**, 1,500 Doubloons
  - **Legendary Treasure Map**, 4,000 Doubloons
- **How buying works:**
  - A bought map always leads to a treasure that is **already out there**. Buying never buries a new one, so you can't keep buying until you get a legendary.
  - Every active treasure's map is sold to **2 or 3 different players** (rolled per treasure), so they race each other.
  - Nobody can buy the same treasure twice. You can own several maps, each to a different treasure.
  - The number of maps for sale follows the number of active treasures. The shop item shows how many are left.
  - When none of that kind are left, it is not for sale and you aren't charged.
- **Settings:**
  - In `treasure.yml` under `shop-maps`: `copies` (default 2-3) and which tiers each kind covers.
  - Prices are in `shop.yml`.

## 2.5.0 (2026-11-13)

### Added
- **Players can see how to craft custom items:**
  - Every custom recipe is unlocked in the vanilla recipe book (the green book in the crafting table).
  - `/recipes` opens a menu with all of them; click one to see its 3x3 layout.

### Changed
- **No treasure map in the starter kit any more:** new players no longer all head for the same few treasures at spawn.
- **Treasure maps never reroll:**
  - Each map is a one-time item tied to its own freshly buried treasure.
  - If someone digs it up first, the map becomes a "Looted Treasure Map".
  - Fixed: after an unsaved server stop, the world could hand out a map id an old map already had, and that old map would suddenly show a new treasure. Those ids are now skipped.
- **Loot rebalanced for every tier, with no totems:**
  - **Rare** (5–7 items, 200–400 Doubloons): diamonds, ender pearls, enchanted diamond helmets and boots, strong books, the flintlock and hand cannon, the Tide Rapier and Marauder's Buckler, Corsair armour pieces, and sealed maps to epic chests.
  - **Epic** (6–8 items, 400–800 Doubloons): diamonds, netherite scrap and ancient debris, enchanted diamond gear, the Sea King's Fork, Mending, the big custom weapons, Tidewarden armour pieces, sometimes an Abyss Fruit, and sealed maps to legendary chests.
  - **Legendary** (7–9 items, 1500–3000 Doubloons) is still the best: netherite, Wavebreaker, Captain's Last Word, Dreadnought Plate, the legendary swords (Tidecleaver, Nightbloom, Leviathan's Fang), the full Tidewarden set, and Abyss Fruits. Its jackpot chance is 12%.
  - **No repeats and jackpots:** every tier now has no-repeat rolls and its own jackpot.

### Updating
- Replace the jar and restart.
- Your rare, epic and legendary loot is replaced only if you never edited it (a backup is kept).
- The starter map switches off automatically unless you changed that setting.

## 2.4.1 (2026-11-13)

### Changed
- **Common and uncommon treasure are worth going after:**
  - **Common chests** (4–6 items, 60–140 Doubloons):
    - iron, gold, copper and coal, and sometimes a diamond
    - food, potions (Diver's Draught, Deckrunner's Tonic) and enchanted books
    - named enchanted gear (Weathered Tricorn, Deckhand's Boots, Rusty Cutlass, Old Salt's Rod)
    - bombs, rum, cannonballs, sometimes a Healing Tonic or a Tide Compass
  - **Uncommon chests** (4–6 items, 90–200 Doubloons):
    - diamonds, golden apples and ender pearls
    - enchanted iron armour and tools, sometimes a diamond Prospector's Pick
    - good enchanted books (Efficiency, Protection, Sharpness, sometimes Mending)
    - Grog of Fury and other potions
    - the flintlock, the Echo Conch and Corsair armour pieces
  - **No repeats:** no item appears twice in the same chest.
  - **Jackpots:** a 5% (common) or 6% (uncommon) chance at one extra standout item, marked "★ Jackpot find!". Examples: diamonds, Mending, the Tide Rapier, an enchanted golden apple, a map to an epic chest, or a pile of Doubloons.
  - **Rare chests:** one more item and more Doubloons (200–400), so they stay above uncommon.
- **`/island home` with several islands:** shows a clickable list to choose from, instead of picking one.
- **`/discord`** now links to the Aquatic SMP Discord.

### Added
- **Sealed treasure maps:**
  - Found in chests: common chests lead to uncommon, uncommon to rare.
  - Right-click to break the seal. A fresh treasure is buried and the item turns into a map with the red X.
  - Admins can hand them out with `/treasure sealedmap <tier> [player]`.
- **Loot options for server owners:**
  - `treasure-map: <tier>` and `potion: <type>` in loot entries
  - `jackpot:` and `unique-rolls:` per tier

### Updating
- Replace the jar and restart. The new loot replaces your common and uncommon loot only if you never edited it (a backup is kept).
- The Discord link is set automatically if `guide.yml` still had the placeholder.

## 2.4.0 (2026-11-13)

### Added
- **The spawn town comes alive (new `hub` module):**
  - Eight market stalls with striped awnings around the throne, each with a shopkeeper villager. Right-click one to open its shop: Blacksmith, Provisioner, Shipwright, Merchant, Carpenter, Quartermaster and Stonemason.
  - A **Crew Registrar** with clickable buttons to found or browse crews.
  - A live **WANTED** board with the top 5 bounties.
  - A **founders' monument**: edit the names in `hub.yml`.
  - A welcome sign.
  - A **parkour tower** with a timer:
    - Start on the gold plate and finish on the iron plate on top.
    - Teleporting, flying or creative mode doesn't count.
    - Pays 250 Doubloons once per 20 hours.
    - `/parkour` shows your best time and the top 5.
  - Built your own spawn? `/hubadmin place <id>` moves an NPC or sign to where you stand.
- **New capital look:**
  - A decorated ring and flower planters around the throne.
  - Proper timber-framed houses in four styles, with gabled roofs, chimneys with smoke, windows, beds and some two-storey houses.
  - Palm trees along the beach.
  - `/spawn` puts you in front of the throne instead of on top of it.
- **`/island home [island]`:** crew members sail to an island their crew owns. It has the same warmup and cooldown as `/spawn` and doesn't work in combat.
- **`/discord`:** set the link in `guide.yml`.
- **Two new shop categories:** Stone & Masonry (37 blocks) and Wood & Decor (37 blocks).
- **Fruits are their own module (`fruits`):** turn them off without touching the other items. Who ate what and the mastery are kept.

### Changed
- **Real caves on every island** (radius 40+):
  - wider tunnels, and big caverns deep down
  - more entrances from the surface
  - they reach about 40 blocks below sea level into the rock under the island
  - blocks next to water stay solid, so caves never flood
  - lake beds are sealed
- **Ores on every island**, each at its own depth:
  - coal, iron and copper near the surface
  - gold, redstone and lapis lower down
  - diamonds deep (reached through the caves)
  - emeralds in the mountains
  - 10 veins per 1000 land blocks (was 4)
- **Bigger islands:**
  - small 60–150, medium 150–275, large 275–450, huge 450–750 (radius)
  - the admin limit is 1000 (was 750)
- **Docks fixed:** the road now crosses the beach as a boardwalk and steps down onto the pier. It is no longer buried under sand.
- **Common and uncommon treasure appear more often:** every 8 minutes (was 20), up to 16 at once (was 8), with higher common and uncommon weights.
- **No travelling in combat:** `/spawn` and `/island home` don't work while you're tagged.
- **Config versions:** existing servers get these new defaults automatically.
  - Only values you never changed are raised.
  - New sections (like the shop categories) are added.
  - A backup `<file>.v0.bak` is kept.

### Updating
- Replace the jar and restart.
- The capital you already have keeps its old look. To get the new town:
  - **Regenerate it** with `/island admin regen <capital>`. This replaces everything on it.
  - **Or keep your own build** and move the NPCs and signs onto it with `/hubadmin place <id>`.
- New caves and ores only appear on islands generated from now on (or regenerated).

## 2.3.1 (2026-11-13)

### Fixed
- **Sidebar coordinates (and other lines) froze** on some clients, and through ViaVersion. A line whose text changes is now removed and re-added, which every client shows.
- **Safer saving:** data files are written to a temporary file first and then swapped in, so a crash during a save can no longer leave a half-written file.
- Fruit mastery XP now autosaves reliably once a minute.

### Changed
- **The last One Piece names are gone.** Item ids, item types, raid mobs and config keys have new names:

  | Old | New |
  | --- | --- |
  | `gomu_gomu` | `springcoil` |
  | `mera_mera` | `emberheart` |
  | `hie_hie` | `rimefrost` |
  | `goro_goro` | `thunderveil` |
  | `suke_suke` | `ghostglass` |
  | `ope_ope` | `riftshift` |
  | `yami_yami` | `hollowvoid` |
  | `ushi_ushi_bison` | `behemoth` |
  | `sea_prism_blade` | `abyssal_baton` |
  | `seastone_cuffs` | `abyssal_shackles` |
  | `wado_ichimonji` | `tidecleaver` |
  | `shusui` | `nightbloom` |
  | `yoru` | `leviathans_fang` |
  | `den_den_mushi` | `echo_conch` |
  | `log_pose` | `tide_compass` |
  | `pirate_king_crown` | `conqueror_crown` |
  | `devil_fruit_random` | `fruit_random` |

  - Item types: `seastone` → `abyssal`, `logpose` → `compass`, `denden` → `conch`.
  - Raid mobs: `marine*` → `tidewatch_*`, `vice_admiral` → `warden_captain`, `admiral` → `high_warden`.
  - Commands: `/devilfruit` → `/abyssfruit`.
  - API: `PirateKingCrownedEvent` → `ConquerorCrownedEvent`.
- **Automatic migration, nothing to do:**
  - Old config files are rewritten on start, with a `<file>.pre-2.3.1.bak` backup.
  - Items players already own get their new id when they join or open a chest.
  - Eaten fruits and fruit mastery carry over.
  - Tested by upgrading a 2.3.0 server that already had these items and fruits.

## 2.3.0 (2026-11-12)

### Changed
- **Intro titles fit on the screen:** every title is short now, and the long text moved to the subtitle and chat. "Become the Conqueror of the Sea" became "The Throne".
  - The steps use the new key `intro.sequence`, so existing servers get the new version automatically.
- **New pirates start with 1000 Doubloons,** enough to found a crew right away.
  - The new key is `start-balance` in `economy.yml`; the old `starting-balance` is no longer used.
  - Players who already joined keep their balance.
  - The starter kit no longer gives extra Doubloons by default.

### Added
- **Expanded sidebar** (new key `layout` in `sidebar.yml`, applied automatically). It shows:
  - your name and level with an **XP bar** (`▮▮▮▮▯▯▯▯▯▯ 40%`)
  - your bounty with its **title** (Rising Tide, Corsair Lord, ...)
  - your crew with **how many are online**
  - the island you're on with its **rarity**
  - your fruit with its mastery and current move
  - **⚔ IN COMBAT** with a countdown
  - the running event
- **New sidebar tags:** `<xp_bar>`, `<xp_percent>`, `<bounty_title>`, `<crew_online>`, `<island_type>`, `<island_rarity>`, `<combat>`.
- **New sidebar conditions:** `?combat:` and `?nofruit:`.
- **API:** `CombatService`, plus `BountyService.tierTitle`, `LevelService.progress` and `IslandService.rarityDisplay`.

## 2.2.0 (2026-11-12)

### Added
- **Crew teleports:** `/tpa <crewmate>`, `/tpahere <crewmate>`, `/tpaccept` and `/tpdeny`.
  - They **only work between crewmates**. Allies can be allowed with `teleport.allow-allies`.
  - The other player gets clickable [Accept] and [Deny] buttons.
  - A 3-second warmup that cancels if you move or take damage, a 60-second cooldown, and requests that expire after 60 seconds.
  - Blocked during combat.
  - Other plugins' teleport requests (EssentialsX `/tpa`, `/call`, `/etpa`, `/tpaall`, ...) are blocked for everyone except admins. The list is in `crews.yml` under `teleport.blocked-commands`.
- **`/island admin removeflag <island>`:** the only way to remove a planted flag. The owners can plant it again with `/island flag`.

### Changed
- **Crew flags are unbreakable:** nobody can break a planted flag (pole and banner), not even admins in creative. Explosions, pistons, fire, flowing water or lava and mobs can't destroy it either. Taking an island only works through conquest.

## 2.1.0 (2026-11-12)

### Added
- **`/spawn`:** sails you back to the spawn set with `/island admin setspawn`. If none is set, it goes to the capital island, and otherwise to the world spawn.
  - A 3-second warmup that cancels if you move or take damage, and a 30-second cooldown.
  - Blocked during combat.
  - Staff can send someone with `/spawn <player>`.
  - Configure it under `spawn.command` in `islands.yml`.
- **Better chat:**
  - Our own chat line, `[12] ★ [TAG] Name » message`, with a level badge, rank icon and crew tag.
  - **@mentions:** an online player's name lights up and that player hears a ping.
  - **`[item]`:** shows the item in your hand in chat, and you can hover over it for all its details.
  - Everything can be configured or turned off under `chat:` in `crews.yml` (for example if you use EssentialsX Chat later).

### Changed
- **The economy commands always belong to AquaticSMP:** `/balance`, `/bal`, `/money`, `/pay` and `/baltop`, even if EssentialsX is installed (also when it loads later). The same goes for `/spawn`.
  - EssentialsX's versions stay reachable as `/essentials:pay` and so on.
  - You no longer need `disabled-commands` in EssentialsX.
  - Turn it off with `take-over-commands: false` (in `economy.yml`) or `spawn.command.take-over: false`.

## 2.0.0 (2026-11-12)

The power update: fruits, gear and progression should really make a difference in PvP.

### Added
- **Stronger abilities:**
  - Fruit moves do **1.5×** their base damage (`abilities.fruit-damage-multiplier`), and sword and gear abilities **1.3×**.
  - Every hit shows sparks, a crit flash and a sound.
- **Fruits give health:** having an Abyss Fruit power gives **+3 hearts** (`fruits.bonus-health`).
- **Fruit mastery (1–20):** train your fruit by using it (XP per use and per hit).
  - Every level gives **+4% damage**, **+2% range** and **-1.5% cooldown** (down to at least 60% of the original), plus **one more heart every 4 levels**.
  - Mastery is per fruit. A level-up shows a title and an effect.
  - `/fruit` shows your mastery. Staff can set it with `/aitem fruit level <player> <level>`.
- **Pirate levels (1–50)** (new `levels` module): XP from PvP kills (worth more against higher levels, and the same player only counts every 10 minutes), monsters, treasure, discoveries and bounties.
  - Every level gives +0.5% damage, and every 5 levels +1 heart. Level 50 is +10 hearts and +24.5% damage.
  - `/level`, `/level top`. Staff: `/level give` and `/level set`.
- **Bounty power:** every bounty tier gives **+1 heart** and **+4% damage**. A Dread Tyrant has +5 hearts and +20% damage, and is therefore also a big target (`power-perks` in `bounty.yml`).
- **Armour sets with recipes:**
  - **Tidewarden** (heavy): diamond, heart of the sea and prismarine crystals. Stronger than diamond, with +5 hearts across the set. Set bonus: Resistance, Water Breathing, Dolphin's Grace and +2 hearts.
  - **Corsair** (light): leather, gold and emeralds. Extra damage and speed. Set bonus: Speed, Jump Boost and +2 hearts.
- **Sidebar:** shows your pirate level and fruit mastery.
- **TODO.md** is completely rewritten: what's done, what's next (priorities) and the backlog per topic.

### Notes
- All bonuses stack: fruit, mastery, level, bounty and armour. Test in-game and adjust the multipliers if PvP gets too fast.
- **Existing servers:** run `/aquatic resetconfig items confirm` for the armour sets, multipliers and mastery settings. Levels and bounty power work right away.

## 1.9.0 (2026-11-12)

### Added
- **Island rarity:** every island type has a rarity, and the random type follows it:

  | Rarity | Types | Weight |
  | --- | --- | --- |
  | Common | Plains, Forest | 10 each |
  | Uncommon | Jungle, Snowy, Desert | 4 each |
  | Rare | Tropical | 2 |
  | Epic | Volcanic, Ancient | 1 each |
  | Legendary | Capital | never random |

  - `/island info` shows the rarity.
  - Discovering a rarer island pays more (×1 up to ×6, `rarities:` in `islands.yml`).
  - The weights are under the new key `generation.island-type-weights`, so they apply on existing servers too.
- **Forest islands:** a new common type with oak, birch and dark oak woods, ferns, berry bushes, lily of the valley and mushrooms, plus its own name list ("Mossbough Grove", "Elderleaf Weald", ...).
- **New swords:**
  - **Stormcaller Saber:** calls down lightning on hit. Move: Arc Lash, chain lightning.
  - **Tide Rapier:** fast and light, with a slowing hit. Move: Riptide Lunge, a dash.
  - **Abyss Cutlass:** steals 20% of the damage as health. Move: Drowning Wave.
  - **Kraken's Maul:** a heavy axe that weakens on hit. Move: Tidal Slam, a shockwave with a windup.
- **Marauder's Buckler:** a shield with armour and knockback resistance in the off hand.
- **Bombs** (new item type `bomb`): right-click to throw. They burst where they land and follow the PvP rules.
  - **Smoke Bomb:** blinds and slows, and the cloud lingers.
  - **Frost Bomb:** freezes and slows.
  - **Fire Bomb:** sets targets on fire without burning blocks.

### Changed
- **Treasure loot updated:**
  - The **elytra is gone**.
  - Bombs are in common to legendary.
  - Healing Tonics are in uncommon and up.
  - The Tide Rapier and Buckler are in rare.
  - The new swords are in epic and legendary.
  - Legendary has new named items: Wavebreaker, Captain's Last Word and Captain's Name Plate, plus a Mending book.
- **Market:** bombs and the Buckler are sold in the Pirate Armory.

### Existing servers
Run `/aquatic resetconfig items confirm`, `treasure` and `shop` for the new items and loot. Backups are kept. Island rarity and the Forest type work right away.

## 1.8.0 (2026-11-12)

### Added
- **Several moves per fruit:** every Abyss Fruit now has 2–3 moves.
  - Pick one with **sneak + scroll** (empty hand). The action bar shows which move you're on.
  - Every move has its own cooldown. The sidebar shows the current move and whether it's ready.
- **New ability types:**
  - `wave`: rolls forward over the ground. It can leave frosted ice on water.
  - `beam`: an instant ray that pierces several targets.
  - `chain`: lightning that jumps from target to target, weaker with every jump.
- **Windup:** any move can have `windup-ticks`, a visible build-up (spiral, shrinking ring and sound, and you slow down) before it fires, so opponents can dodge.
- **New moves:**
  - Springstep, Coil Storm
  - Ember Wave, Pyre Burst
  - Glacier Path, Ice Lance
  - Chain Storm, Stormstep
  - Phantom Step
  - Rift Blade
  - Void Lance
  - Stampede
- **New fruits:**
  - **Galeborn Fruit** (wind): slow falling and high jumps. Moves: Gale Burst, Cyclone, Wind Blade.
  - **Stoneskin Fruit** (stone body): less damage and harder hits. Moves: Quake, Boulder Fist.
- **New gear:**
  - **Coral Trident:** Loyalty, Impaling and Channeling.
  - **Captain's Greatcoat:** unbreakable armour that also makes you faster.
  - **Healing Tonic** and **Grog of the Gale:** drinks.
  - All of them can be found in epic and legendary treasure, and the drinks are also sold in the market.
- **Shipwrecks:** broken hulls half buried in the sand on island beaches, with a snapped mast and a barrel of salvage. Most island types have a 60% chance of one.
- **Lakes:** freshwater ponds with lily pads on flat land, mostly on plains and jungle islands.

### Existing servers
The new moves, fruits and gear are in the default `items.yml`. Run `/aquatic resetconfig items confirm` to get them; a backup is kept. Shipwrecks and lakes work right away.

## 1.7.0 (2026-11-12)

### Added
- **Harbor Market** (new `shop` module):
  - `/shop` opens a chest menu with categories: tools and weapons, provisions, building supplies, sailing and navigation, the pirate armory, and trade goods (the market buys your loot).
  - Left-click buys, shift-left buys a stack, right-click sells, shift-right sells all. `/shop sell` sells what you hold.
  - Custom items can be sold too, such as rum, the flintlock, cannonballs and the Tide Compass.
  - **Edit it in-game:**
    - Hold an item and run `/shop admin add <category> <buy> [sell] [amount]`.
    - `/shop admin list | price | remove`
    - `/shop admin category create | delete`
    - Everything is saved to `shop.yml`.
- **New island upgrades:**
  - **Barracks:** crewmates without a bed respawn on the nearest crew island that has one.
  - **Wellspring:** crewmates regenerate health while on their own island (2 levels).
- **Build access per island:** a button in `/island manage` switches between "all crewmates" and "officers only". Plain members then can't build, break or open containers there.
- **Lore:** the story of the Endless Sea, the drowned empire of the Abyss, Abyss Fruits, Abyssal Iron, the Tidewatch of the Harbor Crown and the throne of the Conqueror of the Sea.
  - It's in every item's description.
  - It's in the new intro, which opens with **"Sail. Claim. Conquer."**
  - The guide book has a new first chapter, "The Endless Sea", plus pages for the market and island upgrades.
- **Renamed abilities:** Ice Age → Frostburst, Invisibility → Mistveil, Beast Form → Behemoth Rage.

### Fixed
- **Volcano craters could hold lava with no wall around it.** The lava level was fixed in advance, while the real rim height varies with hills and mountains.
  - The crater is now filled after the terrain, to just below the lowest point of the rim, so the lake is always enclosed.
  - 2 real lava streams spill over the rim and run down the volcano (`volcano.lava-streams`).

### Existing servers
Run `/aquatic resetconfig items confirm` and `/aquatic resetconfig guide confirm` to get the new lore and intro. A backup of the old file is kept.

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
