# Cyanite Client

A Fabric utility client for Minecraft 1.21.11, built around DonutSMP: base hunting, PvP, a clean HUD and a lot more.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 1.21.11, and [Fabric API](https://modrinth.com/mod/fabric-api).
2. Download `cyanite_client-<version>.jar` from the [latest release](../../releases/latest) and put it in your `mods` folder.
3. Start the game and press **Insert** to open the menu. With [Mod Menu](https://modrinth.com/mod/modmenu) installed, Cyanite's **Configure** button there opens it too.

Keep only one Cyanite jar in `mods`, or the game won't start.

## Updates

Cyanite updates itself. When you start the game, it checks this repository's releases. If a newer one is out, it downloads it, and the new jar replaces the old one once you close the game. Change this under **Look & feel → Updates**: *Install* (the default), *Tell me* (just a message with a download link), or *Off*. With Mod Menu installed, its update badge shows a new release too.

## Online count

The title screen and the watermark show how many people have Cyanite open. To be counted, your game proves which account it's on with the chat key Mojang gives every Microsoft account, the one servers use to check signed chat: Cyanite's counter hands it a challenge, the game signs it with that key and sends the key's Mojang-signed certificate, and the counter checks both. Your access token and private key never leave your game. The counter stores only keyed hashes of your account and connection, never your UUID or IP, and drops you when you close the game (or a few minutes later if it crashes). One account counts once however many games it has open, and only a few accounts count per connection. Offline accounts aren't counted but still see the number. Turn it off under **Look & feel → Online count**, and nothing is sent at all.

## Is my jar the real one?

Only download Cyanite from this repository's [releases](../../releases). Copies passed around elsewhere can have a RAT or token stealer packed in.

Each time the game starts, Cyanite hashes its own jar and compares it with the SHA-256 GitHub lists for that release. If they differ, or the version was never released here, the title screen warns you before anything else. Someone who edits the jar can also cut that check out, so you can check by hand too: the release page shows each jar's `sha256:`, and on Windows `Get-FileHash cyanite_client-<version>.jar` in PowerShell prints the one you have. They must be the same.

## Coming from another client

If Meteor Client, Wurst or Xaero's Minimap has left its settings in your game folder, Cyanite offers to copy them the first time it finds them. Each Meteor or Wurst module is matched to the Cyanite one that does the same job: what you had on, your keybinds and the settings that mean the same thing come over, and each Meteor profile becomes a Cyanite profile. Meteor's and Xaero's waypoints go into Cyanite's Waypoints, on the same server's or world's list. What you had before is saved as a profile (*Before Meteor*, *Before Wurst*) first, a key Cyanite already uses stays as it is, and the other clients' files are only read. To do it later, use **Profiles → Import**.

## Modules

| Category | Modules |
| --- | --- |
| Combat | Aim Assist, Anti Bot, Auto Anchor, Auto Armor, Auto Clicker, Auto Gap, Auto Log, Auto Totem, Auto Weapon, Bow Aimbot, Criticals, Hitboxes, Kill Aura, Pop Counter, Surround, Trigger Bot, Velocity |
| Movement | Auto Elytra, Auto Trident, Auto Walk, Boat Fly, Flight, Jesus, No Clip, No Fall, No Slow, Safe Walk, Speed, Spider, Sprint, Step |
| Render | Acid Mode, Block ESP, Breadcrumbs, Cinematic, ESP, F5 Camera, FOV Changer, Freecam, Fullbright, Lyrics, Motion Blur, Nametags, No Fog, No Hurt Cam, Pearl ESP, Search, Seed Xray, Small Totem, Storage ESP, Tracers, Villager ESP, Waypoints, Xray, Zoom |
| Player | Auto Eat, Auto Fish, Auto Respawn, Chest Stealer, Fast Place, Hotbar Refill, Inventory Move |
| World | Area Miner, Auto Mine, Auto Tool, Bedrock Burrow, Chest Index, Fast Break, Fast Mine, Scaffold, Schematic Builder, Timer |
| Misc | Anti AFK, Anti Mod Check, Anti Vanish, Base Guard, Clips, Death Position, Discord RPC, Message Logger, Name Protect, Pets, Stealth Mode, Streamer Mode |
| Donut SMP | Auction Sniper, Auto Sell, Base Alert, Bounty & Balance, Chunk Change Finder, Deepslate Bypass, Depth Alert, Drill Logger, Drill Tester, Fake Pay, Hidden Player Finder, Leak Finder, Relog, Scoreboard Spoof, Sound ESP, Spawner Finder, Staff List, Sus Chunk Finder |

Settings shown in **red** are ones anticheats catch. Use them only where that's allowed, and use Cyanite at your own risk.

## Visuals

The **Visuals** tab (next to Modules in the classic menu, at the top of the Beta UI's sidebar) changes how the game looks, for you only: nothing it does is sent to the server. Each group has a switch and its own settings, sections fold open and shut, the search box finds a group by its name or any of its settings, and a live preview shows what each one does as you change it.

There are six visual profiles: General, Crystal PvP, Sword PvP, Axe PvP, Pot PvP and UHC / SMP. Each keeps its own settings, so every kind of fight can have its own crosshair, hit colours and particles. With **Switch by held item** on, the profile follows what's in your hand (crystals, a sword, an axe, splash potions, a bow). Everything is saved to `cyanite/visuals.json`.

| Section | Groups |
| --- | --- |
| General Visuals | Totem Pop, Item Colors, Low Fire, Custom Crosshair, Block Highlight, Enchantment Glint, Damage Tint, Potion Effects, Weather Visuals, Custom Particles, Custom HUD, Armor Durability |
| Crystal PvP | End Crystal Size, Crystal Animation, Obsidian & Bedrock Colors, Explosion Particles, Crystal Beam, Totem Pop, Highlight Crystals, Custom Explosion Effects |
| Sword PvP | Sword Swing Animation, Sword Trail, Sword Hit Particles, Critical Hit Particles, Custom Hit Colors, Damage Indicators, Sword Attack Animation |
| Axe PvP | Axe Swing Animation, Shield Break Effects, Axe Hit Particles, Damage Indicators, Axe Attack Animation |
| Pot PvP | Splash Particles, Projectile Trails, Potion Effects, Health Indicators, Potion Impact Effects |
| UHC / SMP | Arrow Trails, Hit Particles, Golden Apple Eating, Armor Durability, Damage Indicators, Projectile Effects |
| General PvP | Custom Hit Colors, Hurt Camera, Target Highlight, Custom Crosshair, Totem Pop, Enchantment Glint, Custom Particles, Projectile Trails |

## Building

You need JDK 21 or newer.

```sh
./gradlew build
```

The jar ends up in `build/libs/`.

## Releasing

Every push to `main` is built by GitHub Actions. When the push changes `version` in `gradle.properties` to a version that hasn't been released yet, the workflow also publishes a release `v<version>` with the jar attached, and everyone's client picks it up on their next start. So releasing is: bump the version, commit, push.

## Credits

Some of the base-finding methods come from the Anubis client (GPL-3.0): Sus Chunk Finder's guess at where someone sits from the server's simulation distance, reading amethyst the server hides by its light, and most of its plant signs; Hidden Player Finder's "simulation ring", finding players by what ticks round them; Chunk Change Finder's trick of having the server resend the chunks round you and comparing them with what it sent before; and Leak Finder's reading of the block entities a chunk names, before the client drops the ones the server hides. Cyanite's code is its own, written from those ideas. Thanks to the Anubis devs.

Cinematic's space sky pictures are from Kenney's "Skyboxes Space" pack ([kenney.nl](https://kenney.nl), CC0), cleaned up for the sky.

## License

[GPL-3.0](LICENSE). Forks and changes are welcome, as long as they stay open source under the same license.
