# Torch Planner

<img src="src/main/resources/assets/torchplanner/icon.png" width="96" alt="Torch Planner icon">

Client-side Fabric mod for Minecraft 26.3 that highlights where to put torches
so no hostile mob can spawn near you, using as few torches as it can.

- **Gold boxes**: place a torch here.
- **Red squares** (toggleable): blocks a monster can spawn on right now.
- **Purple squares**: dark spots that no floor torch in range can light. Light them by hand
  (wall torch, lantern, or slab them over).

Toggle with **F7** (rebindable under Controls → Torch Planner) or from
**Mod Menu → Torch Planner → Configure**. It works on servers because it only reads the
world your client already has.

The plan is made **once**, when you turn it on (or join a world), and then stays fixed:
walking around or placing torches never moves the markers. A gold marker disappears as
soon as every spot it was meant to light is lit (even if you put your torch a block off),
and red squares disappear as their blocks get lit. When the last gold marker is gone it
plans again around you. To replan earlier, for example after moving to a new area, use
**Mod Menu → Torch Planner → Rescan**. Radius changes apply on the next scan.

## How it decides

Since 1.18, overworld monsters only spawn at **block light 0**. A torch emits light
level 14, and light drops by at least 1 per block, so on open ground a torch lights a
diamond reaching 13 blocks in each direction (365 floor blocks). Diamonds of that
size tile a flat floor perfectly when torches sit on a fixed grid. That's the
"diamond pattern" rule.

That rule breaks as soon as there are walls, pits, pillars or overhangs, because light
has to travel around them. So instead of assuming the pattern, the mod:

1. Snapshots the blocks around you and finds every **dark spawnable spot**, using vanilla's
   own ground-spawn check (zombie-sized mob) and the current block light.
2. For every block a floor torch could go, **simulates vanilla block-light propagation**
   (same per-face occlusion and dampening as the game's light engine) to see which dark
   spots it would light.
3. Picks torches with greedy set cover: repeatedly take the spot that lights the most
   still-dark blocks, then drop any torch the others made redundant. Ties go to the
   diamond grid; it tries 16 grid alignments.
4. Cleans up the best few plans with a merge pass. Greedy's early picks leave odd-shaped
   leftovers that later torches mop up, often squeezed in close to earlier ones. For each
   pair of nearby torches it looks for one spot that lights everything only those two light,
   and swaps the pair for it, until nothing more merges. The plan with the fewest torches wins.
   On rough cave terrain this saves about 10% of torches and cuts torches crammed within
   12 blocks of each other by about 40%.

Torches may be suggested up to 13 blocks outside the scan area when that lights its edge
with fewer torches. The light simulation runs on all but one CPU core, which takes about
0.3 s at the default radius.

The scan radius goes from 16 up to 256 blocks. Big scans read the world a few milliseconds
per tick so the game doesn't freeze, then plan in 128×128 tiles, centre first, to keep
memory bounded. A flat 256-radius area takes about 6 s and plans within about 22% of the
perfect diamond tiling. The action bar shows progress. You can only plan what your client
has loaded, so radius 256 needs a render distance of at least 17 chunks; otherwise the
unloaded part is skipped and the message says so. Gold markers are drawn up to 256 blocks
away; red and purple squares only within 4 chunks of you.

Other light-level mods mostly just paint spawnable blocks red (Light Overlay, MiniHUD)
or snap markers to a fixed grid (Gleam's torch markers). This mod does the red overlay
too, but its torch markers come from simulating the actual terrain.

In the Nether and other dimensions where monsters ignore block light, it shows nothing:
torches can't help there.

## Build

Needs JDK 25+. Your shell's `JAVA_HOME` points at 17, so override it:

```sh
JAVA_HOME=/usr/lib/jvm/java-27-openjdk ./gradlew build
```

`build` also copies the jar into the Prism Launcher instance at
`~/.local/share/PrismLauncher/instances/26.3/minecraft/mods` (and removes older builds of
this mod there). Use `-PprismModsDir=/other/mods` for a different instance, or
`./gradlew installToPrism` to copy without rebuilding.

`./gradlew test` runs the solver tests on synthetic terrain; `./gradlew runClient` starts
a dev client with Mod Menu.

Requires Fabric API. Mod Menu is optional.
