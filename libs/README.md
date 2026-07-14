# libs/

`build.gradle` expects two compile-only jars here:

```
xaerolib-1.1.0-ab30ae2ca666ba3a.jar
xaerolib-1.1.15-8d2180f17290a347.jar
```

These are XaeroLib, a small shared support library used by Xaero's Minimap and
Xaero's World Map — not something this project owns or has rights to
redistribute, so they're gitignored rather than committed.

To build locally, obtain the matching versions from your own installed copy of
Xaero's Minimap / Xaero's World Map (it ships alongside those mods) and drop
the jars in this folder before running `./gradlew build`. If the exact
filenames above don't match what you have, update the `modCompileOnly
files(...)` lines in `build.gradle` to point at whatever version you're
building against — they're compile-only, so the version just needs to be
API-compatible with what's declared in `build.gradle`'s `xaeroplus`/
`xaeroworldmap`/`xaerominimap` dependency versions.
