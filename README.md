# Impostor SMP (Fabric, Minecraft 26.3)

2 secret impostors among the players. Innocents win by killing both impostors or the Ender Dragon; impostors win by killing every innocent.

## Installing

Everybody needs Minecraft 26.3, Fabric Loader 0.20.1+, Fabric API (0.165.0+26.3 or newer) and the jar in their `mods` folder. The jar must be installed BEFORE the world is created, because it also changes stronghold generation to a single stronghold.

## Playing

- `/imp start` (host): picks the impostors, runs the 3-2-1 countdown, then shows IMPOSTOR (red) or INNOCENT (green) on each player's screen.
- `/imp role`: shows your own role again.
- `/imp rules` and `/imp rule <name> <true/false/number>`: toggle any rule (anti-RDM, totems, chat, death sound, ...).
- `/imp status`, `/imp stop`, `/imp reveal` (after a game), `/imp lock`.
- Create the world as Hardcore. Random spawn spreading can be done with your own command blocks, or turn on the `spread_on_start` rule.
