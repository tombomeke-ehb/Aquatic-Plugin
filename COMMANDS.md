# AquaticSMP: Commands

## What this plugin does

AquaticSMP turns a survival server into a a pirate world. In one plugin:

- **Pirate crews**: Captains, First Mates, Navigators and Quartermasters, with a shared treasury, a crew home and a flag. Crews can form alliances, declare rivalries and go to war.
- **Doubloons**: the server currency. Shop and job plugins can use it too, through Vault.
- **Bounties**: put a price on a pirate's head. Infamous killers get wanted posters and titles from *Rookie* up to *Emperor*.
- **Islands**: generate tropical, jungle, volcanic, snowy, desert, ancient and capital islands with one command. Crews plant their flag on an island to claim it and defend it. Enemy crews take it over by holding the flag.
- **Treasure**: buried chests with loot tiers, and treasure maps with a red X.
- **Pirate gear**: Abyss Fruits with powers (and the sea's curse), Abyssal Iron weapons, legendary swords, flintlocks, cannons, the Tide Compass, rum and more.
- **The Conqueror of the Sea tournament**: registration, qualifiers, an elimination bracket and a best-of-3 finale. The winning captain is crowned Conqueror of the Sea and enters the Hall of Kings.
- **Server events**: Treasure Hunt, Pirate War, Bounty Hunt, Lost Island, King's Challenge, Grand Raid and Naval Battle.

Every feature is a **module** you can turn on or off. All numbers, costs and messages are in the config files.

## How to read this

- In-game, every help page (`/crew help`, `/island help`, ...) can be hovered for details and clicked to fill in the command.

- `<value>` is required, `[value]` is optional, and `a|b` means pick one.
- **Player** permissions are given to everyone by default. Remove one with LuckPerms to lock that feature.
- **Admin** permissions are given to ops only. `aquatic.admin` grants every admin command.
- Each command works only while its module is on (see `/aquatic modules`).
- Every command also has a long form, `/aquatic:<command>`, which keeps working when another plugin uses the same name. See [EssentialsX conflict](#essentialsx-conflict).

---

## Server management (core)

| Command | What it does | Permission |
| --- | --- | --- |
| `/aquatic` or `/aquatic about` | Plugin version and credits (developed by tombomeke). Admins also see the admin help | Everyone |
| `/aquatic commands` | Every command in the plugin, clickable (aliases `cmds`, `all`) | Everyone |
| `/aquatic modules` | List all modules and whether they are on | `aquatic.admin` |
| `/aquatic enable <module>` | Turn a module on (saved in `config.yml`) | `aquatic.admin` |
| `/aquatic disable <module>` | Turn a module off; modules that depend on it go off too | `aquatic.admin` |
| `/aquatic reload [module]` | Reload configs for all modules or just one, with no restart | `aquatic.admin` |
| `/aquatic resetconfig <module> confirm` | Replace a module's config with the newest defaults. The old file is kept as a `.bak` | `aquatic.admin` |

Aliases: `/aq`, `/aquaticsmp`. Module names: `economy`, `crews`, `bounty`, `combat`, `levels`, `islands`, `treasure`, `items`, `tournament`, `events`, `guide`, `sidebar`, `shop`.

---

## Economy: Doubloons (`economy`)

| Command | What it does | Permission |
| --- | --- | --- |
| `/doubloons [player]` | Show your Doubloons, or another player's | Player. Checking others needs `aquatic.economy.balance.others` |
| `/pay <player> <amount>` | Send Doubloons to another pirate | Player: `aquatic.economy.pay` |
| `/baltop [page]` | The richest pirates | Player |
| `/doubloons give <player> <amount>` | Add Doubloons (works for offline players) | Admin: `aquatic.economy.admin` |
| `/doubloons take <player> <amount>` | Remove Doubloons | Admin: `aquatic.economy.admin` |
| `/doubloons set <player> <amount>` | Set an exact balance | Admin: `aquatic.economy.admin` |

Aliases:
- `/doubloons`: `/balance`, `/bal`, `/money`
- `/baltop`: `/doubloonstop`, `/richest`

Amounts accept `k`, `m` and `b`, e.g. `/pay Zoro 2.5k`.

---

## Crews (`crews`)

**`/crew` opens the crew menu: you can run your whole crew by clicking.** The commands below still work, but you don't need them.
- **Overview:** your crew's info and members.
- **Members:** officers click a member to promote, demote, give any rank, make them captain (captain only) or kick them.
- **Bank:** deposit or withdraw 100, 1,000 or 10,000, or deposit everything.
- **Invite:** pick a pirate without a crew from a list.
- **Diplomacy:** every other crew with your relation. Click one to ask for an alliance, go neutral, mark them as rivals, declare war or offer peace.
- **Other buttons:** crew home (right-click to set it), your islands, the flag designer, crew chat, friendly fire, and leaving or disbanding.
- **Confirmation:** anything that can't be undone (kick, war, giving away the captaincy, leaving, disbanding) asks first.
- **Without a crew:** found one, browse all crews, or click an invite to join.

`/crew` (alias `/c`)

**Membership**

| Command | What it does | Who |
| --- | --- | --- |
| `/crew create <name> [tag]` | Found a new pirate crew (may cost Doubloons) | Anyone without a crew |
| `/crew invite <player>` | Invite a player (the invite expires after a while) | Officers |
| `/crew accept [crew]` | Accept an invite | Invited player |
| `/crew deny [crew]` | Decline an invite | Invited player |
| `/crew leave` | Leave your crew. A captain must `transfer` first, unless they are the last member | Members |
| `/crew kick <player>` | Throw a member overboard | Officers |
| `/crew disband [confirm]` | Disband the crew. The bank is refunded to the captain | Captain |

**Ranks**

Ranks are Captain, First Mate, Navigator, Quartermaster and Member. `crews.yml` sets limits per rank and what each rank may do.

| Command | What it does | Who |
| --- | --- | --- |
| `/crew promote <player>` | One rank up (Member → Navigator → First Mate) | Captain |
| `/crew demote <player>` | One rank down | Captain |
| `/crew setrank <player> <rank>` | Give a specific rank, e.g. Quartermaster | Captain |
| `/crew transfer <player>` | Hand the captaincy to someone else | Captain |

**Information**

| Command | What it does |
| --- | --- |
| `/crew info [crew\|player]` | Members, ranks, reputation, relations and war score |
| `/crew list [page]` | All crews |
| `/crew top [page]` | Most famous crews by reputation |

**Identity and base**

| Command | What it does | Who |
| --- | --- | --- |
| `/crew color <color>` | Crew colour for the name and chat tag | Officers |
| `/crew tag <tag>` | Short tag shown in chat and the tab list | Officers |
| `/crew flag` | Show your flag. With a banner in your hand: make it the crew flag | Members / officers |
| `/crew flag set` | Use the banner in your hand as the crew flag. Design any banner you like in a loom | Officers |
| `/crew flag color <dye>` | New base colour, same design (`red`, `black`, `light_blue`, ...) | Officers |
| `/crew flag preset <name>` | Use a ready-made design | Officers |
| `/crew flag presets` | List the designs: `crew_colors`, `jolly_roger`, `crossbones`, `blood_skull`, `sea_kraken`, `sun_pirates`, `navy` | Everyone |
| `/crew flag get` | Get a copy of the flag banner | Members |
| `/crew flag reset` | Back to the default flag in the crew colour | Officers |
| `/crew sethome` | Set the crew home where you stand | Officers |
| `/crew home` | Teleport home after a warmup. Moving or taking damage cancels it; there is a cooldown | Members (`aquatic.crews.home`) |

**Crew teleports** (only between crewmates; 3 s warmup, 60 s cooldown, not in combat)

| Command | What it does | Who |
| --- | --- | --- |
| `/tpa <crewmate>` | Ask to teleport to a crewmate | Members |
| `/tpahere <crewmate>` | Ask a crewmate to come to you | Members |
| `/tpaccept [player]` | Accept a request (or click [Accept]); alias `/tpyes` | Members |
| `/tpdeny [player]` | Deny a request (or click [Deny]); alias `/tpno` | Members |

Teleport commands of other plugins (EssentialsX `/tpa`, `/call`, ...) are blocked.

**Chat, friendly fire and bank**

| Command | What it does | Who |
| --- | --- | --- |
| `/crew chat` | Toggle whether your normal messages go to crew chat | Members |
| `/cc [message]` | Send one crew chat message, or toggle crew chat (alias `/crewchat`) | Members |
| `/crew friendlyfire` | Toggle whether crewmates can hurt each other (alias `ff`) | Captain |
| `/crew bank balance` | Show the crew treasury | Members |
| `/crew bank deposit <amount>` | Put Doubloons in the treasury | Members |
| `/crew bank withdraw <amount>` | Take Doubloons out | First Mate and Quartermaster by default |

**Diplomacy**

| Command | What it does | Who |
| --- | --- | --- |
| `/crew ally <crew>` | Propose an alliance, or accept one (both sides must agree) | Officers |
| `/crew rival <crew>` | Declare a rival | Officers |
| `/crew neutral <crew>` | End an alliance or rivalry | Officers |
| `/crew war <crew>` | Declare war. Announced server-wide, can cost Doubloons from the bank, and opens up raiding and conquest | Officers |
| `/crew peace <crew>` | Offer or accept peace. Both sides must agree, after a minimum war duration | Officers |

**Admin** (`aquatic.crews.admin`)

| Command | What it does |
| --- | --- |
| `/crew admin disband <crew>` | Force-disband a crew |
| `/crew admin setcaptain <crew> <player>` | Change the captain |
| `/crew admin setrep <crew> <amount>` | Set reputation |
| `/crew admin join <crew> [player]` | Force-add yourself or a player to a crew |
| `/crew admin kick <player>` | Force-remove a player from their crew |

---

## Bounties (`bounty`, needs `economy`)

| Command | What it does | Permission |
| --- | --- | --- |
| `/bounty set <player> <amount>` | Put a price on a pirate's head. You pay it, plus a tax. Aliases: `place`, `add` | Player: `aquatic.bounty.set` |
| `/bounty info [player]` | A pirate's bounty and title (Rookie, Rising Tide, Warlord, Emperor, ...) | Player |
| `/bounty top [page]` | Most wanted pirates | Player |
| `/bounty list [page]` | Every active bounty, online pirates first | Player |
| `/wanted [player]` | Get a "WANTED: DEAD OR ALIVE" poster item | Player: `aquatic.bounty.wanted` |
| `/bounty admin add <player> <amount>` | Raise a bounty for free | Admin: `aquatic.bounty.admin` |
| `/bounty admin remove <player> <amount>` | Lower a bounty | Admin |
| `/bounty admin clear <player>` | Remove a bounty | Admin |

Alias: `/bounties`.

Killing a pirate with a bounty pays you the full amount. Crewmates and allies of the victim cannot claim it. PvP kills also raise the killer's own bounty, and this grows on a killstreak.

---

## Spawn

| Command | What it does | Permission |
| --- | --- | --- |
| `/spawn` | Back to spawn (the point from `/island admin setspawn`, otherwise the capital). 3 s warmup, cancelled if you move or get hurt; not in combat | Everyone |
| `/spawn <player>` | Send someone to spawn right away | Admin: `aquatic.islands.admin` |
| `/island home [island]` | Sail to an island your crew owns (next to its flag). If the crew owns several, you get a clickable list to pick from. Same warmup and cooldown as `/spawn`; not in combat | Crew members |
| `/discord` | The server's Discord invite (set the link in `guide.yml` under `discord.link`) | Everyone |

## Spawn town hub (`hub`)

The capital's plaza has eight market stalls with shopkeeper villagers (right-click to open that shop), a Crew Registrar, a live **WANTED** bounty board, the founders' monument (edit the names in `hub.yml`), a welcome sign and a parkour tower.

| Command | What it does | Permission |
| --- | --- | --- |
| `/parkour` | Your best parkour time and the top 5. Start on the gold plate at the foot of the tower, finish on the iron plate on top (250 Doubloons once per 20 hours) | Everyone |
| `/hubadmin list` | All NPCs and signs, and whether they are placed by hand | `aquatic.hub.admin` |
| `/hubadmin place <id>` | Move an NPC or sign to where you stand (for your own spawn build) | `aquatic.hub.admin` |
| `/hubadmin reset <id>` | Put it back at its default spot in the generated town | `aquatic.hub.admin` |
| `/hubadmin refresh` | Respawn all NPCs and signs | `aquatic.hub.admin` |

---

## Islands (`islands`)

`/island` (aliases `/is`, `/islands`)

**Player commands**

| Command | What it does | Permission |
| --- | --- | --- |
| `/island info [island]` | Type, size, status, owner and shield of an island | `aquatic.islands.info` |
| `/island here` | Which island you are standing on | `aquatic.islands.info` |
| `/island list [page]` | All islands with their coordinates and distance. Admins get a clickable **[TP]** button per island | `aquatic.islands.list` |
| `/island map` | Nearest islands with direction and distance (alias `near`) | `aquatic.islands.map` |
| `/island discovered [player]` | Islands you have discovered | `aquatic.islands.discovered` |
| `/island top` | Crews ranked by islands owned | `aquatic.islands.top` |
| `/island claim` | Plant your crew flag on the unclaimed island you stand on (officers, may cost Doubloons) | `aquatic.islands.claim` |
| `/island unclaim [confirm]` | Abandon the island you stand on | `aquatic.islands.claim` |
| `/island flag` | Move or re-place your crew flag to where you stand | `aquatic.islands.flag` |
| `/island manage [island]` | Your island's stats and upgrade menu | `aquatic.islands.manage` |
| `/island upgrade <treasury\|fortress\|watchtower\|harbor\|barracks\|wellspring\|vault\|granary>` | Buy the next upgrade level for the island you stand on (Captain/First Mate, paid from the crew bank) | `aquatic.islands.manage` |
| `/island facility` | The facilities menu (also in `/island manage`): pick a building to preview | `aquatic.islands.manage` |
| `/island facility preview <watchtower\|forge\|farm\|dock\|storehouse\|lighthouse>` | Show a preview in front of you (only you see it) | `aquatic.islands.manage` |
| `/island facility build\|rotate\|move\|cancel` | Build the previewed facility (Captain/First Mate, crew bank), turn it, move it to where you stand, or remove the preview. The chat buttons do the same | `aquatic.islands.manage` |
| `/island facility remove` | Demolish the facility you stand in (part of the price back; chest contents drop) | `aquatic.islands.manage` |
| `/island facility list` | The facilities on this island | `aquatic.islands.manage` |
| `/island vault [island]` | Open your crew's vault on that island (Vault upgrade: 9/27/54 slots, conquerors take what's inside) | `aquatic.islands.manage` |

**Admin commands** (`aquatic.islands.admin`)

Run `/island admin` for this list in-game. The short form without `admin` (e.g. `/island spawn`) also works, but only `/island admin` shows these in help and tab completion.

| Command | What it does |
| --- | --- |
| `/island admin spawn <type> <radius> [name]` | Generate an island at your position. Types: `plains`, `forest`, `tropical`, `jungle`, `volcanic`, `snowy`, `desert`, `ancient`, `tidewatch`, `village`, `capital` |
| `/island admin spawnrandom <type\|random> <count> [minRadius] [maxRadius]` | Scatter many islands over the ocean area, auto-named and spaced apart |
| `/island admin area <radius> [spacing]` | Set the ocean area for random islands and treasure, centred on you |
| `/island admin register <name> <type> <radius>` | Turn terrain you already built into an island (no generation) |
| `/island admin delete <island> [restore]` | Remove an island. `restore` turns it back into ocean |
| `/island admin regen <island> [newseed]` | Wipe and regenerate the terrain |
| `/island admin cancel [island]` | Stop island generation, including a running `spawnrandom`. Islands that were half built stay registered; remove them with `/island admin delete <island> restore` |
| `/island admin setspawn` | Make the island you stand on the neutral spawn island |
| `/island admin setstatus <island> <unclaimed\|neutral>` | Change status. Neutral = safe zone: no PvP, building, claiming or explosions |
| `/island admin setowner <island> <crew\|none>` | Give an island to a crew, or free it |
| `/island admin rename <island> <new name>` | Rename an island |
| `/island admin setradius <island> <radius>` | Change the protected radius |
| `/island admin shield <island> <minutes>` | Protect an island from conquest for a while |
| `/island admin removeflag <island>` | Remove an island's flag. Flags can't be broken by anyone (admins included); this is the only way |
| `/island admin tp <island>` | Teleport to an island |

Bypass all island protection: `aquatic.islands.bypass`.

**Island upgrades:** Treasury (more income), Fortress (slower capture), Watchtower (alerts and glowing intruders), Harbor (faster swimming, water breathing), Barracks (crew respawn point) and Wellspring (healing). In `/island manage` you can also limit building to officers. They're bought from the crew bank. After a conquest, every upgrade drops a level.

**Flags can't be broken** by players, admins, mobs, explosions, pistons, fire or water. An island changes hands only through conquest.

**Conquest:** stand near an enemy island's flag with your crew. The capture bar fills while no defender is close and drains otherwise. When it is full, the island is yours.

---

## Treasure (`treasure`)

`/treasure` (alias `/tr`)

| Command | What it does | Permission |
| --- | --- | --- |
| `/treasure info` | Rumours about how much treasure is buried and where | Player: `aquatic.treasure.info` |
| `/treasure spawn <tier> [here\|random]` | Bury a treasure where you stand or at a random spot. Tiers: `common`, `uncommon`, `rare`, `epic`, `legendary` | Admin: `aquatic.treasure.admin` |
| `/treasure map <tier> [player]` | Bury a treasure and give a treasure map to it | Admin |
| `/treasure list` | All active treasures, numbered | Admin |
| `/treasure tp <number>` | Teleport to a treasure from the list | Admin |
| `/treasure remove <number\|all>` | Remove treasures, chest included | Admin |
| `/treasure give-loot <tier>` | Preview a loot roll (the items go to you) | Admin |
| `/treasure loot add <tier> [weight]` | Add the item in your hand to a tier's loot (custom items, crate keys and enchantments are kept) | `aquatic.treasure.admin` |
| `/treasure loot list <tier>` / `/treasure loot remove <tier> <number>` | See or remove a tier's loot entries | `aquatic.treasure.admin` |
| `/treasure sealedmap <tier> [player]` | Hand out a sealed treasure map. The treasure is only buried when the map is opened (right-click) | Admin |

Right-click a treasure map to get a "warmer / colder" hint.

---

## Items and Abyss Fruits (`items`, fruits: `fruits`)

Fruits are their own module: `/aquatic disable fruits` (or `fruits: false` in `config.yml`) switches all fruit powers off. Nobody can eat a fruit, the bonus hearts go away and treasure stops dropping random fruits. Who ate what and the mastery are kept, so `/aquatic enable fruits` brings everything back.


| Command | What it does | Permission |
| --- | --- | --- |
| `/fruit` | Your Abyss Fruit power, its ability and cooldown (alias `/devilfruit`) | Player: `aquatic.items.fruit` |
| `/recipes` | A menu with every custom crafting recipe; click one to see its 3x3 layout. All custom recipes are also unlocked in the vanilla recipe book | Everyone |
| `/aitem give <player> <id> [amount]` | Give a custom item | Admin: `aquatic.items.admin` |
| `/aitem list` | All custom item ids | Admin |
| `/aitem fruit set <player> <fruit\|none>` | Give or remove an Abyss Fruit power directly | Admin |
| `/aitem fruit info [player]` | Show a player's power and mastery | Admin |
| `/aitem fruit level <player> <level>` | Set someone's fruit mastery (1–20) | Admin |

Aliases: `/aquaticitem`, `/pirateitem`.

Default item ids:
- **Abyss Fruits:** `springcoil`, `emberheart`, `rimefrost`, `thunderveil`, `ghostglass`, `riftshift`, `hollowvoid`, `behemoth`, `galeborn`, `stoneskin`
- **Weapons:** `abyssal_baton`, `abyssal_shackles`, `tidecleaver`, `nightbloom`, `leviathans_fang`, `storm_saber`, `tide_rapier`, `abyss_cutlass`, `krakens_maul`, `harpoon`, `coral_scimitar`, `abyssal_anchor`, `musket`, `flintlock`, `hand_cannon`, `cannonball`
- **Throwables (right-click):** `smoke_bomb`, `frost_bomb`, `fire_bomb`
- **Tools and other:** `deepcore_pickaxe`, `tide_compass`, `echo_conch`, `rum`, `healing_tonic`, `gale_grog`, `conqueror_crown`
- **Gear:** `coral_trident`, `captains_coat`, `marauders_buckler`
- **Armour sets (craftable):** `tidewarden_helmet`, `tidewarden_chestplate`, `tidewarden_leggings`, `tidewarden_boots`, `corsair_hat`, `corsair_coat`, `corsair_breeches`, `corsair_boots`
- **Special:** `fruit_random` gives a random Abyss Fruit

**Using an Abyss Fruit power:** sneak + **F** (swap hands), or sneak + right-click while looking at a block. Fruits have 2–3 moves: **sneak + scroll** (empty hand) switches between them. You cannot use powers while in water.

---

## Conqueror of the Sea tournament (`tournament`, needs `crews`)

`/tournament` (aliases `/tourney`, `/pkt`)

**Player commands**

| Command | What it does | Permission |
| --- | --- | --- |
| `/tournament register` | Register your crew (officers) | `aquatic.tournament.register` |
| `/tournament unregister` | Withdraw your crew | `aquatic.tournament.register` |
| `/tournament roster [add\|remove <member>]` | Show or choose your crew's fighters | `aquatic.tournament.register` |
| `/tournament info` | Current stage and the reigning Conqueror of the Sea | `aquatic.tournament.view` |
| `/tournament standings` | Qualifier points | `aquatic.tournament.view` |
| `/tournament bracket` | The elimination bracket | `aquatic.tournament.view` |
| `/hallofconquerors` | Every past Conqueror of the Sea with date, crew and season (aliases `/conquerors`, and the old `/hallofkings` and `/kings`) | `aquatic.tournament.view` |

**Staff commands** (`aquatic.tournament.admin`)

| Command | What it does |
| --- | --- |
| `/tournament open [minutes]` | Open registration. 0 = open until you run `start` |
| `/tournament start` | Move to the next stage: registration → qualifiers → bracket |
| `/tournament next` | Start the next bracket match |
| `/tournament status` | Detailed state of the tournament |
| `/tournament forcewin <crew>` | Award the current or next match to a crew |
| `/tournament cancel` | Cancel the tournament |
| `/tournament arena create <name>` | Create an arena |
| `/tournament arena setspawn <name> <1\|2>` | Set team 1 or team 2 spawn at your position |
| `/tournament arena setspectator <name>` | Set where eliminated players watch |
| `/tournament arena delete <name>` | Delete an arena |
| `/tournament arena list` | List arenas |
| `/tournament setlobby` | Where players return after a match |
| `/tournament admin setking <player\|none>` | Set or clear the Conqueror of the Sea by hand |

Nobody loses items in a match. Position, health and inventory are restored afterwards.

---

## Combat (`combat`)

These rules keep PvP fair. Tournament matches use their own rules instead.

- **Combat tag:** when you hit a player or get hit, both of you are *in combat* for 15 seconds. A red bar shows at the top of the screen.
  - You cannot use escape commands such as `/crew home`, `/spawn`, `/home`, `/tpa` or `/warp`, or teleport by command.
  - Logging out in combat kills you: your items drop and your attacker gets the kill and any bounty.
- **Respawn protection:** after respawning you are immune to other players for 10 seconds, so nobody can camp spawn. Attacking someone ends it.
- **New-player protection:** for your first 30 minutes of playtime you can't attack players and they can't attack you. Use `/pvp on` to end it early.

| Command | What it does | Permission |
| --- | --- | --- |
| `/pvp` | Your protection and combat status (alias `/combat`) | Everyone |
| `/pvp on [confirm]` | Give up new-player protection early | Player: `aquatic.combat.optout` |
| `/pvp status [player]` | Status of yourself, or another player (admins) | Everyone |
| `/pvp untag <player>` | Remove a player's combat tag | Admin: `aquatic.combat.admin` |
| `/pvp protect <player>` | Give back new-player protection, if they are still under the playtime limit | Admin: `aquatic.combat.admin` |

Staff with `aquatic.combat.bypass` never get tagged. Everything is in `combat.yml`, including the list of blocked commands.

---

## Pirate levels (`levels`)

**Level rewards:** every 5 levels gives Doubloons, items and a title (Deckhand … Legend of the Sea). Each reward is given once; players who were already past a level get it on their next login. `/level rewards` lists them all, and the title shows in `/level` and on the sidebar.

| Command | What it does | Permission |
| --- | --- | --- |
| `/level [player]` | Your (or someone's) level, XP and bonuses (aliases `/lvl`, `/levels`) | Everyone |
| `/level top` | The highest-level pirates | Everyone |
| `/level give <player> <xp>` | Give XP | Admin: `aquatic.levels.admin` |
| `/level set <player> <level>` | Set a level | Admin: `aquatic.levels.admin` |

XP comes from PvP kills, monsters, treasure, discovering islands and claiming bounties. Every level gives a little extra damage, and every 5 levels an extra heart.

---

## Smithy (`smithy`)

| Command | What it does | Permission |
| --- | --- | --- |
| `/smithy` (aliases `/forge`, `/blacksmith`) | Upgrade the item in your hand (+1 … +5). Costs Doubloons and materials and has a success chance per level; a failed attempt costs the materials but never breaks the item. The Blacksmith villager at spawn opens it too | Everyone |

What the upgrades do:
- Weapons get more attack damage.
- Armour gets more armour and toughness.
- Pickaxes, shovels and hoes get extra Efficiency.
- Bows get extra Power, and crossbows get extra Quick Charge.

## Tidewatch outposts

TIDEWATCH islands (`/island admin spawn tidewatch <radius>`, or random spawns) have a fort with a garrison.

- **The garrison:** while players are on the island, soldiers keep coming, and the Warden Captain guards the keep.
- **Liberating:** kill the captain to liberate the island. Every attacker on the island gets 1,500 Doubloons and XP, and the fort's strongbox is buried in the keep.
- **Claiming:** a crew can claim the island for 12 hours afterwards. Unclaimed forts get a new garrison after that.
- **Settings:** everything is under `tidewatch` in `islands.yml`.

## Schematics: your own builds

Put builds in `plugins/AquaticSMP/schematics`: **`.schem`** files from WorldEdit or FastAsyncWorldEdit, or **`.nbt`** files saved with a structure block.
- Save a build with its **entrance on the north side**. If it faces another way, say so with `front: south` (or `east`/`west`) where you use it.
- Its bottom layer replaces the ground. Use `y-offset: -1` to sink it one block.
- Only the blocks are copied. Chest contents and sign text are not copied yet.

There are three ways to use a build:

1. **By hand (spawn builds):** `/island admin schematic preview <file>` shows a ghost of it in front of you. Then click:
   - [Paste]: rises with the build animation.
   - [Paste now]: placed at once.
   - [Rotate], [Move here] or [Cancel].
   - [Undo] puts back everything your last paste replaced.
2. **Automatically on islands:** list builds per island type under `builds` in `islands.yml`, for example on the `CAPITAL`.
   - They are placed every time an island of that type is generated.
   - `/island admin builds <island>` places them on an existing island.
3. **As facilities:** give a facility type a `schematic` in `islands.yml`.
   - It replaces our built-in design, or adds a brand-new facility (with its own price, limit and income bonus) that crews can buy and place with the usual preview.

| Command | What it does | Permission |
| --- | --- | --- |
| `/island admin schematic list` | The files in the schematics folder (click one to preview it) | Admin |
| `/island admin schematic preview <file> [north\|east\|south\|west]` | A ghost of the build in front of you; the last word is the side its entrance was on when saved | Admin |
| `/island admin schematic rotate\|move\|cancel` | Turn it, move it to where you stand, or remove the preview | Admin |
| `/island admin schematic paste [instant]` | Build it (with the animation, or at once with `instant`) | Admin |
| `/island admin schematic undo` | Put back what your last paste replaced | Admin |
| `/island admin builds <island>` | Place the `builds` from `islands.yml` for that island's type | Admin |

## Facilities

Crews can build real buildings on their islands: a **Watchtower**, a **Forge** (anvil, smithing table, blast furnace, grindstone), a **Farm Field**, a **Dock**, a **Storehouse** and a **Lighthouse**.

1. Open `/island manage` and click **Facilities**, then pick one.
2. **Preview:** the building appears in front of you as a ghost only you can see, with its outline on the ground (green = fits, red = doesn't). Blocks of your own builds in the way show as red glass, and chat lists any problems. Walk around it to see how it looks.
3. Click **[Rotate]** or **[Move here]** until it is right, then **[Build]**. It is paid from the crew bank (Captain and First Mate).
4. **Construction:** the site is cleared, then the building goes up from the ground, block by block, with blocks flying into place. Crewmates nearby see a progress bar. It takes about 30 seconds (the Lighthouse 45).

Some facilities raise the island's income (Forge, Farm, Storehouse, Lighthouse +5%, Dock +10%). Prices, limits, build time and the animation are under `facilities` in `islands.yml`.

## Villages and quests (`quests`, needs `economy`)

VILLAGE islands (`/island admin spawn village <radius>`, or random spawns) are neutral: they are already lived in, so nobody can claim them, build there or fight there.
- **The village:** cottages, a smithy, two fishing huts, a tavern and fenced farm fields along dirt roads to the coast, with a well in the middle and piers at the ends of the roads.
- **The villagers:** four quest givers stand around the well: the Village Elder, the Old Fisher, the Village Smith and the Hunter. Right-click one to see their quests.
- **Quests:** catch fish, hunt mobs or bring materials. Rewards are Doubloons, pirate XP and sometimes items. Each quest can be done again after a cooldown (usually 20–48 hours). You can work on 3 at once.
- **Settings:** quests and givers are in `quests.yml`; the villagers' names, looks and spots are in `hub.yml` (`island-type: VILLAGE`).

| Command | What it does | Permission |
| --- | --- | --- |
| `/quests` (alias `/quest`) | Your quests and how far along you are | Everyone |
| `/quests accept <quest>` | Accept a quest (on a village; the [Accept] button does this) | Everyone |
| `/quests turnin <quest>` | Hand in a finished quest (on a village) | Everyone |
| `/quests abandon <quest>` | Give up a quest | Everyone |

## Crates (`crates`)

Every crate has its own block: nether blocks nobody can get with the Nether off.
- The Sailor's Crate is Nether Quartz Ore, the Captain's is Nether Gold Ore, and the Kraken's Hoard is Shroomlight (it glows).
- The resource pack turns them into 3D crates.
- **Placing one:** `/crate item <crate>` gives staff the block; place it and it is a crate. `/crate setup` places three stands at the capital you stand on.
- **Any other block** can be a crate too (`/crate set <crate>` while looking at it), for example a chest in a pasted schematic.
- **Opening:** right-click a crate with its key. A reel spins and stops on a prize.
- **Prizes:** left-click (or right-click without a key) shows every prize with its chance. `/crate` shows them for every crate.
- **Keys:** they come from treasure (uncommon and up), from `/crate give`, and from any reward list in the configs as `key:<crate>:<amount>` (quests, level rewards, crate prizes, treasure loot).
- **Big wins** are announced. Crates, prizes, chances and key looks are in `crates.yml`.

| Command | What it does | Permission |
| --- | --- | --- |
| `/crate` (alias `/crates`) | See the crates and click one for its prizes | Everyone |
| `/crate preview <crate>` | Every prize of a crate and its chance | Everyone |
| `/crate item <crate>` | Get the crate's block; placing it makes a crate | `aquatic.crates.admin` |
| `/crate set <crate>` / `/crate remove` | Make the block you look at a crate, or stop it being one | `aquatic.crates.admin` |
| `/crate setup` | Default crate stands at the capital (`setup-spots` in `crates.yml`) | `aquatic.crates.admin` |
| `/crate list` | Every crate block | `aquatic.crates.admin` |
| `/crate give <player> <crate> [amount]` / `/crate giveall <crate> [amount]` | Give keys | `aquatic.crates.admin` |

## Gambling den (`gambling`, needs `economy`)

Doubloons only, and only at the capital. Players have **no gambling commands**: they talk to the **Dealer** at spawn and play from his menu. A coin flip is started by picking a player and a bet; the other player accepts or turns it down at the Dealer.

**The games:**
- **Dice:** two dice against the dealer's two. Higher wins double; a tie gives half back.
- **Wheel of fortune:** spin for a multiplier, from nothing up to x10.
- **Coin flip:** against another player. The house keeps 5% of the pot.

**Limits** (all in `gambling.yml`): pirate level 5 or higher, bets from 10 to 5,000, 25,000 a day, and 3 seconds between bets. Big results are written to `data/gambling.log`.

| Command | What it does | Permission |
| --- | --- | --- |
| `/gamble` (alias `/casino`) | The den's menu, anywhere (for testing) | `aquatic.gambling.admin` |

**A den building:** paste one with `/island admin schematic preview builtin:gambling_den`. It's a striped tent with a dealer's counter, a dice table and three crate pedestals. Make the pedestals crates with `/crate set`, and move the Dealer in with `/hubadmin place dealer`.

| Command | What it does | Permission |
| --- | --- | --- |

## Harbor Market (`shop`, needs `economy`)

**Only through the shopkeepers:** players have **no `/shop` command**. They talk to the shopkeeper villagers at spawn, and each one opens its own market page (the Blacksmith's tools button too). Staff keep `/shop`. `command.players-can-open: true` in `shop.yml` gives everyone the command back.

**Treasure maps** (Sailing category): a Treasure Map (common to rare), an Epic and a Legendary Treasure Map.
- Each one leads to a treasure that already exists. Each treasure's map is sold to 2–3 different players, so it's a race.
- You can't buy the same treasure twice, and a kind is sold out when no such treasure is left.

| Command | What it does | Permission |
| --- | --- | --- |
| `/shop [category]` | Open the market (alias `/market`). Left-click buy, shift-left buy a stack, right-click sell, shift-right sell all | `aquatic.shop.admin` |
| `/shop sell` | Sell everything like the item in your hand | `aquatic.shop.admin` |
| `/shop admin add <category> <buy> [sell] [amount]` | Add the item in your hand to a category | Admin: `aquatic.shop.admin` |
| `/shop admin list <category>` | List a category's items with their numbers | Admin |
| `/shop admin price <category> <number> <buy> <sell>` | Change a price (0 = not bought / not sold) | Admin |
| `/shop admin remove <category> <number>` | Remove an item | Admin |
| `/shop admin category <create\|delete> <id> [icon] [slot]` | Add or remove a category | Admin |

---

## Sidebar (`sidebar`)

| Command | What it does | Permission |
| --- | --- | --- |
| `/sidebar [on\|off]` | Show or hide the stats sidebar (alias `/sb`) | Everyone |

All lines are configurable in `sidebar.yml`.

---

## Guide and intro (`guide`)

New players get a short intro on their first join: titles and chat tips about crews, islands and flags, treasure digging, bounties, Abyss Fruits and the Conqueror of the Sea. When it ends, the guide book opens.

| Command | What it does | Permission |
| --- | --- | --- |
| `/guide` | Open the pirate's guide book (aliases `/tutorial`, `/pirateguide`) | Everyone |
| `/guide <chapter>` | Open one chapter: `welcome`, `crews`, `islands`, `treasure`, `bounties`, `fruits`, `conqueror` | Everyone |
| `/guide chapters` | List the chapters | Everyone |
| `/guide intro` | Watch the intro again | Player: `aquatic.guide.use` |
| `/guide skip` | Stop the intro | Everyone |
| `/guide tips [on\|off]` | Turn the tips in chat on or off | Everyone |
| `/guide kit <player>` | Give a player the starter kit | Admin: `aquatic.guide.admin` |
| `/guide play <player>` | Play the intro for a player | Admin: `aquatic.guide.admin` |
| `/guide reset <player\|all>` | Show the intro again on their next join | Admin: `aquatic.guide.admin` |

Brand-new players also get a **starter kit**: a boat, food, a stone shovel and sword, a Tide Compass, 100 Doubloons, and a **treasure map to a real buried treasure**, so treasure hunting is the first thing they learn.

New players (under 5 hours of playtime) also get a short **tip** in chat every 10 minutes.

All texts, timing, kit items and tips are in `guide.yml`. For testing, set `intro.mode: every-join`.

---

## Server events (`events`)

`/seaevent` (aliases `/event`, `/sevent`)

| Command | What it does | Permission |
| --- | --- | --- |
| `/seaevent info` | Running events and time left | Player: `aquatic.events.info` |
| `/seaevent join [type]` | Join a running event (teleports you there if configured) | Player: `aquatic.events.join` |
| `/seaevent start <type> [args]` | Start an event | Admin: `aquatic.events.admin` |
| `/seaevent stop [type] [finish]` | Stop events. `finish` = end with results and rewards | Admin |
| `/seaevent list` | All event types | Admin |
| `/seaevent schedule <on\|off>` | Toggle automatic random events | Admin |
| `/seaevent setlocation <event> [name] [remove]` | Save your position as a location for an event | Admin |

Event types:

| Type | What happens |
| --- | --- |
| `treasure_hunt` | A legendary treasure is buried, with hints that narrow down over time. First to dig it up wins |
| `pirate_war <crewA> <crewB>` | An official timed war between two crews with a kill scoreboard. Without names, two rival or warring crews are picked |
| `bounty_hunt` | Extra bounties go on the most wanted pirates. The hunter who claims the most wins. Unclaimed event bounties are removed afterwards |
| `lost_island` | A new island rises from the sea, full of high-tier treasure, and sinks again when the event ends |
| `kings_challenge` | King of the hill on the capital island, or on a location you set. The crew that holds it longest wins |
| `grand_raid` | Waves of Tidewatch soldiers attack, ending with a High Warden boss. Rewards are split by damage dealt |
| `naval_battle` | Kills at sea or in boats score points for your crew. Cannons can be handed out at the start |

---

## EssentialsX conflict

Depending on the version, EssentialsX can also have `/balance`, `/bal`, `/money`, `/pay`, `/baltop`, `/tpa` and (with EssentialsX Spawn) `/spawn`.

**AquaticSMP always wins these names** (`/tpa` only works between crewmates; EssentialsX's tpa commands are blocked), even if EssentialsX loads later. You don't have to change anything in EssentialsX.
- EssentialsX's own versions stay reachable as `/essentials:pay`, `/essentials:spawn` and so on.
- To give those names back to EssentialsX: set `take-over-commands: false` in `economy.yml` and `spawn.command.take-over: false` in `islands.yml`.

**Recommended setup with EssentialsX:**
1. Install **EssentialsX** (core) for homes, warps, `/tpa`, `/msg` and kits.
2. Install **Vault**. It's a separate plugin, not part of EssentialsX. AquaticSMP then registers Doubloons with Vault, so EssentialsX's `/sell` and `/worth` and other plugins pay in Doubloons.
3. **EssentialsX Spawn: not needed.** AquaticSMP handles spawn, first-join and respawn locations and `/spawn` itself. If you install it anyway, set `respawn-listener-priority: none` and `spawn-on-join: false` in its config.
4. **EssentialsX Chat: not needed.** AquaticSMP formats chat itself (crew tags, level badges, mentions, `[item]`). If you do use it, set `chat.format.enabled: false` in `crews.yml` and add `%aquatic_crew_tag%` (needs PlaceholderAPI) to its format.

**Other overlaps:**
- **Tab list:** AquaticSMP's crew tags in the tab list can fight with the **TAB** plugin. If you use TAB, set `tablist.enabled: false` in `crews.yml` and use the `%aquatic_crew_tag%` placeholder (needs PlaceholderAPI) in TAB instead.
- **Chat:** with an EssentialsX Chat format plugin, both may try to format the message. Either set `chat.prefix-enabled: false` in `crews.yml` or put `%aquatic_crew_tag%` in your chat plugin's format.
