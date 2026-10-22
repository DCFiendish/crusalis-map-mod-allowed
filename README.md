# Crusalis Map Mod

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
- **Warzones** — the mid-week warzone event's zones get a red outline and their name
  (with a sword icon). Each warzone territory is filled in its current holder's color
  and its label names the holder, since warzone land is only ever captured, not annexed.
- **Resource icons** — each node's resource icons, as on map.crusalis.net, with a
  filter to single out one resource type.
- **Chunk grid** — an optional chunk grid on both maps.
- All of the above are individually toggleable via
  [Mod Menu](https://modrinth.com/mod/modmenu) / [Cloth Config](https://modrinth.com/mod/cloth-config), or the settings key (default O).

This build only changes the map node by node. It has no per-chunk war markers
(captured-chunk or under-attack stripes).

## Requirements

- Fabric Loader, Fabric API
- Minecraft 1.21.11
- [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) 1.46.0 or newer
- [Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) 26.5.0 or newer, for the minimap overlay
- Cloth Config; Mod Menu is optional

XaeroPlus is not needed: the overlay draws straight into Xaero's maps (see
`docs/xaero-hooks.md`).

## Building

Standard Fabric/Loom build:

```
./gradlew build
```

For IDE setup, see the [Fabric documentation](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up).

## License

All Rights Reserved — see `LICENSE`.
