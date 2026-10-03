# Crusalis Map Mod

REQUIRES EXACT XAEROS VERSIONS. DO NOT BOTHER ME IF YOU DO NOT HAVE THE CORRECT VERSIONS.

A Fabric client mod that adds a live nation/territory overlay to Xaero's
Minimap and World Map, built for the [Nodes](https://nodes.soy/) town/nation
plugin. It polls the server's public map-data endpoints and Minecraft chat to
keep the overlay in sync in near real time — nation-colored territory fills,
resource node borders/labels, town and nation name labels, and port markers.

## Features

- **Nation territory fills** — every claimed territory tinted by its owning
  nation's color, with the home/core chunk of each territory marked distinctly.
- **Resource node borders & labels** — territory outlines and resource-type
  labels (diamonds, gold, iron, etc.) for nodes with resources.
- **Town & nation labels** — town names at their spawn point; nation names at
  each nation's capital.
- **Ports** — port markers.
- **Occupied territories** — driven live off in-game `[War]` chat messages: a
  diagonal marker across a captured-but-not-yet-annexed territory, in the
  occupier's color, while the base fill still shows the original owner.
- All of the above are individually toggleable via
  [Mod Menu](https://modrinth.com/mod/modmenu) / [Cloth Config](https://modrinth.com/mod/cloth-config).

## Requirements

- Fabric Loader, Fabric API
- [XaeroPlus](https://modrinth.com/mod/xaeroplus) 2.30.10+fabric-1.21.11
- [Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) 25.3.10
- [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) 1.40.11
- Cloth Config, Mod Menu

## Building

See `libs/README.md` for one manual step (a couple of third-party jars aren't
redistributed in this repo). Otherwise, standard Fabric/Loom build:

```
./gradlew build
```

For IDE setup, see the [Fabric documentation](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up).

## License

All Rights Reserved — see `LICENSE`.
