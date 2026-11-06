# Impostor SMP (Fabric, Minecraft 26.3)

2 secret impostors among the players. Innocents win by killing both impostors or the Ender Dragon; impostors win by killing every innocent.

## Getting the .jar

The jar has to be built once from this folder. Pick one:

**A. On your PC (needs JDK 25)**
1. Install JDK 25 (e.g. Temurin from adoptium.net).
2. Open a terminal in this folder and run `./gradlew build` (Windows: `gradlew.bat build`).
3. Use the jar in `build/libs/` that does NOT end in `-sources.jar`.

**B. On GitHub, nothing to install**
1. Make a free GitHub repository and upload everything in this folder, including the hidden `.github` folder.
2. Open the repo's **Actions** tab, wait for the `build` run to turn green.
3. Open the run, download **Artifacts**, unzip, and use the jar that does NOT end in `-sources.jar`.

If the build fails, copy the red error lines and send them back.

## Installing

Everybody needs Minecraft 26.3, Fabric Loader 0.20.1+, Fabric API (0.165.0+26.3 or newer) and the jar in their `mods` folder. The jar must be installed BEFORE the world is created, because it also changes stronghold generation to a single stronghold.

## Playing

- `/imp start` (host): picks the impostors, runs the 3-2-1 countdown, then shows IMPOSTOR (red) or INNOCENT (green) on each player's screen.
- `/imp role`: shows your own role again.
- `/imp rules` and `/imp rule <name> <true/false/number>`: toggle any rule (anti-RDM, totems, chat, death sound, ...).
- `/imp status`, `/imp stop`, `/imp reveal` (after a game), `/imp lock`.
- Create the world as Hardcore. Random spawn spreading can be done with your own command blocks, or turn on the `spread_on_start` rule.
