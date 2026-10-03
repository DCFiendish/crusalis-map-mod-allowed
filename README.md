# Crusalis Map Mod (Node Overlay Safe)

REQUIRES EXACT XAEROS VERSIONS. DO NOT BOTHER ME IF YOU DO NOT HAVE THE CORRECT VERSIONS.

A Fabric client mod that adds a live nation/territory overlay to Xaero's
Minimap and World Map, built for the [Nodes](https://nodes.soy/) town/nation
plugin. It polls the server's public map-data endpoints and Minecraft chat to
keep the overlay in sync in near real time: nation-colored territory fills,
resource node borders, labels and icons, and town and nation name labels.

This is the safe variant. The map only ever changes node by node, never chunk by chunk.
There are no per-chunk war markers (chunk captures, attacks in progress); only whole
territories change color or get marked occupied.

It draws straight into Xaero's maps. XaeroPlus is not needed.

## Features

- **Nation territory fills:** every claimed territory is tinted with its owning nation's
  color, and the home/core chunk of each territory is marked distinctly. Opacity is a
  slider.
- **Resource node borders and labels:** territory outlines and resource-type labels.
  Borders hide automatically when you zoom far out.
- **Resource icons:** the same icons map.crusalis.net uses, downloaded at runtime. You can
  replace them with your own PNGs in `config/aechronismapmod/icons/` (see the README.txt
  there). Icons stay a fixed size at every zoom.
- **Resource filter:** show only the nodes of one resource type. There's a setting for it
  and a keybind that cycles through the types.
- **Town and nation labels:** town names at their spawn point, nation names at each
  nation's capital.
- **Occupied territories:** driven live by in-game `[War]` chat messages. A diagonal
  marks a captured-but-not-yet-annexed territory in the occupier's color, while the base
  fill still shows the original owner.
- **Chunk grid:** optional, with color, opacity and width settings.
- Everything is individually toggleable. Press **O** for the settings, or use
  [Mod Menu](https://modrinth.com/mod/modmenu).

## Requirements

- Minecraft 1.21.11, Java 21+
- Fabric Loader 0.19.2+, Fabric API (1.21.11)
- [Cloth Config](https://modrinth.com/mod/cloth-config) 21.11.153 (1.21.11)
- [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) 1.46.0+
- [Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) 26.5.0. Optional, but needed
  for the minimap overlay.
- [Mod Menu](https://modrinth.com/mod/modmenu) 17.0.1. Optional; the O key opens the
  settings too.

## Building

Standard Fabric/Loom build:

```
./gradlew build
```

The jar ends up in `build/libs/`. `./gradlew runClient` starts a dev client with Xaero's
maps. See `docs/xaero-hooks.md` for how the overlay hooks into Xaero and how to run the
self-test.

For IDE setup, see the [Fabric documentation](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up).

## License

All Rights Reserved — see `LICENSE`.
