# Cyanite Client

A Fabric utility client for Minecraft 1.21.11, built around DonutSMP: base hunting, PvP, a clean HUD and a lot more.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 1.21.11, and [Fabric API](https://modrinth.com/mod/fabric-api).
2. Download `cyanite_client-<version>.jar` from the [latest release](../../releases/latest) and put it in your `mods` folder.
3. Start the game and press **Insert** to open the menu. With [Mod Menu](https://modrinth.com/mod/modmenu) installed, Cyanite's **Configure** button there opens it too.

Keep only one Cyanite jar in `mods`, or the game won't start.

## Updates

Cyanite updates itself. When you start the game, it checks this repository's releases. If a newer one is out, it downloads it, and the new jar replaces the old one once you close the game. Change this under **Look & feel → Updates**: *Install* (the default), *Tell me* (just a message with a download link), or *Off*. With Mod Menu installed, its update badge shows a new release too.

## Modules

| Category | Modules |
| --- | --- |
| Combat | Aim Assist, Anti Bot, Auto Anchor, Auto Armor, Auto Clicker, Auto Gap, Auto Log, Auto Totem, Auto Weapon, Bow Aimbot, Criticals, Hitboxes, Kill Aura, Pop Counter, Surround, Trigger Bot, Velocity |
| Movement | Auto Walk, Boat Fly, Flight, Jesus, No Clip, No Fall, No Slow, Safe Walk, Speed, Spider, Sprint, Step |
| Render | Block ESP, Breadcrumbs, ESP, F5 Camera, Freecam, Fullbright, Motion Blur, Nametags, No Fog, No Hurt Cam, Pearl ESP, Search, Small Totem, Storage ESP, Tracers, Villager ESP, Waypoints, Xray, Zoom |
| Player | Auto Eat, Auto Fish, Auto Respawn, Chest Stealer, Fast Place, Hotbar Refill, Inventory Move |
| World | Area Miner, Auto Mine, Auto Tool, Bedrock Burrow, Chest Index, Fast Break, Fast Mine, Scaffold, Schematic Builder, Timer |
| Misc | Anti AFK, Anti Mod Check, Anti Vanish, Base Guard, Clips, Death Position, Discord RPC, Message Logger, Name Protect, Pets, Stealth Mode, Streamer Mode |
| Donut SMP | Auto Sell, Base Alert, Bounty & Balance, Depth Alert, Drill Logger, Drill Tester, Fake Pay, Relog, Scoreboard Spoof, Spawner Finder, Staff List, Sus Chunk Finder |

Settings shown in **red** are ones anticheats catch. Use them only where that's allowed, and use Cyanite at your own risk.

## Building

You need JDK 21 or newer.

```sh
./gradlew build
```

The jar ends up in `build/libs/`.

## Releasing

Every push to `main` is built by GitHub Actions. When the push changes `version` in `gradle.properties` to a version that hasn't been released yet, the workflow also publishes a release `v<version>` with the jar attached, and everyone's client picks it up on their next start. So releasing is: bump the version, commit, push.

## License

[GPL-3.0](LICENSE). Forks and changes are welcome, as long as they stay open source under the same license.
