package com.dcfiendish.aechronismapmod;

import com.google.gson.*;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.ChunkPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AechronisMapData {

    // ── Nation fill chunk colors ────────────────────────────────────────────
    // NOT volatile-swapped wholesale anymore — mutated IN PLACE, incrementally,
    // by three different callers: the towns.json poll diff (see loadTownsData),
    // chat-driven node flips (captureTerritory), and read
    // by the renderer every time it needs a fresh alpha-cache snapshot. Because
    // it's mutated in place rather than replaced, every access — read AND write —
    // goes through nationChunksLock. Do not touch nationChunksRaw directly from
    // outside this class; use the synchronized helper methods below.
    private final Long2LongOpenHashMap nationChunksRaw = new Long2LongOpenHashMap();
    private final Object nationChunksLock = new Object();

    // ── Geometry derived purely from world.json ─────────────────────────────
    // world.json is fetched ONCE per client session (see AechronisDataFetcher —
    // fetchWorldAndTerritories runs once via schedule(), never repeats; only
    // fetchTownsJson repeats every 60s). So all of this is built exactly once,
    // in rebuildGeometry() below (called from loadWorldData), and never touched
    // again for the rest of the session. Border lines, node labels, and town
    // shapes genuinely never change live — only OWNERSHIP changes, and that's
    // driven entirely by towns.json polls, handled separately in loadTownsData.
    public volatile List<NodeBorderLine> nodeBorderLines = new ArrayList<>();
    public volatile Long2ObjectOpenHashMap<NodeLabelInfo> nodeLabelInfos = new Long2ObjectOpenHashMap<>();
    public volatile Map<String, Set<Long>> territoryChunkMap = new HashMap<>();
    public volatile Map<String, Long> coreChunkMap = new HashMap<>();
    public volatile Map<String, List<NodeBorderLine>> territoryDiagonals = new HashMap<>();

    // ── Town-derived data — recomputed every towns.json poll, but cheap: this
    // loop is proportional to TOWN COUNT (low hundreds at most), never to chunk
    // count, so there's no need to diff/cache this part. ──────────────────────
    public volatile Long2ObjectOpenHashMap<NodeLabelInfo> townLabelInfos = new Long2ObjectOpenHashMap<>();
    // Nation labels — one per nation, placed at the nation's CAPITAL town spawn, lifted
    // a small fixed offset north so it sits above the town label. Rebuilt every towns
    // poll (cheap, proportional to nation count). Filler nations (Impassable, Wilderness)
    // are skipped. Keyed by a synthetic position key like the others.
    public volatile List<NationLabelInfo> nationLabelInfos = new ArrayList<>();
    /** Node type (lower case, e.g. "wheat") -> map.crusalis.net icon key (e.g. "wheat"), from world.json "nodes". */
    public volatile Map<String, String> nodeTypeIcons = Map.of();
    public volatile List<TownWaypoint> townWaypoints = new ArrayList<>();
    public volatile Map<String, String> townNationMap = new HashMap<>();
    // Player username -> nation. Built each towns.json poll from each town's
    // AUTHORITATIVE "residents" UUID roster (which always includes the leader —
    // confirmed against the plugin source), not a resident's own self-reported
    // "town"/"nation" fields (same staleness concern as townNationMap above).
    // Exists specifically so captureTerritory() can resolve the attacker's
    // USERNAME from chat (see AechronisChatListener) to a nation color — the
    // chat broadcast never contains a town name, only the acting player's name.
    public volatile Map<String, String> playerNationMap = new HashMap<>();

    // ── Occupation / annexation (captured-but-not-annexed) tracking ─────────
    // Per Nodes plugin mechanics (confirmed via https://nodes.soy/4-2-diplomacy-war.html):
    // capturing a territory's home/core chunk puts the WHOLE territory into "occupied"
    // state immediately — the occupying town gets tax income but does not yet own it.
    // towns.json splits each town's territory IDs into "territories" (founding claims),
    // "annexed" (finalized), and "captured" (occupied, not yet annexed). This set holds
    // whichever territory IDs are CURRENTLY in the "captured" bucket. Kept correct by:
    //   1. Every towns.json poll rebuilds this from JSON truth — authoritative,
    //      self-correcting, and the same diff pass already walks every owned
    //      territory for the color check below, so this costs nothing extra.
    //   2. captureTerritory() (chat-driven) also adds to it immediately, for instant
    //      feedback between polls; the next poll corrects it either way.
    // No chat-driven REMOVAL exists yet (no confirmed annex chat string — see
    // annexTerritory() below) — removal only happens via the JSON poll, which is
    // sufficient on its own since it's authoritative.
    public final Set<String> capturedTerritoryIds = ConcurrentHashMap.newKeySet();
    public final ConcurrentHashMap<String, Integer> territoryDiagonalColors = new ConcurrentHashMap<>();

    // Set to true only when an actual OWNERSHIP change was applied this poll (new
    // owner, or an owning nation's color itself changed) — NOT set unconditionally
    // every poll anymore. The renderer's alpha-cache/border-cache rebuilds key off
    // this, so they now only redo work when something genuinely changed, instead
    // of every 60 seconds regardless. This is intentionally time-agnostic: it
    // doesn't matter whether changes happen on schedule, late, or off-schedule —
    // it only reacts to towns.json actually differing from last poll.
    public volatile boolean dirty = false;

    private JsonObject worldData = null;

    private volatile Map<String, Integer> gistColors = new HashMap<>();
    private volatile Map<String, Integer> nationColors = new HashMap<>();

    // Last-applied color per territory (null/absent = currently unowned), used
    // purely as poll-to-poll diff bookkeeping. Mostly touched from the fetcher
    // thread (loadTownsData runs on AechronisDataFetcher's single-thread scheduler),
    // but liberateTerritory() (chat-driven, runs on the client thread) also writes
    // to it directly — so it's a ConcurrentHashMap, not a plain HashMap.
    private final Map<String, Integer> lastTerritoryColor = new ConcurrentHashMap<>();

    // Grace window for chat-driven optimistic flips. When captureTerritory() flips a
    // node from a chat message, it stamps the territory id -> timestamp here. The
    // ownership diff in loadTownsData() will NOT revert a territory that was chat-flipped
    // within the last CHAT_FLIP_GRACE_MS, because towns.json may not have regenerated to
    // reflect the capture yet — reverting it would cause a visible flicker (instant
    // correct flip -> brief revert to old owner -> flip back once the server catches up).
    // After the window expires, the poll resumes full authority over that territory.
    // Written by the chat thread (captureTerritory) and read by the fetcher thread
    // (loadTownsData), so it's a ConcurrentHashMap.
    private final ConcurrentHashMap<String, Long> chatFlipTimestamps = new ConcurrentHashMap<>();
    // Makes the multi-collection updates to capturedTerritoryIds / territoryDiagonalColors /
    // chatFlipTimestamps atomic across the three writers: the towns poll (fetcher thread),
    // captureTerritory() and annexTerritory() (chat thread). Without it a chat capture or
    // liberation landing mid-poll is clobbered by the poll's stale snapshot.
    private final Object occupiedStateLock = new Object();
    private static final long CHAT_FLIP_GRACE_MS = 90_000L;

    public static final int CORE_CHUNK_SENTINEL = 0x969696;

    public void loadGistColors(JsonObject json) {
        Map<String, Integer> newGistColors = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            String nation = e.getKey();
            JsonElement val = e.getValue();
            if (val.isJsonArray()) {
                JsonArray c = val.getAsJsonArray();
                if (c.size() >= 3) {
                    newGistColors.put(nation, rgb(c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt()));
                    System.out.println("[Crusalis] Gist color override: " + nation);
                }
            }
        }
        this.gistColors = newGistColors;
        System.out.println("[Crusalis] Loaded " + newGistColors.size() + " Gist color overrides");
    }

    /**
     * Called exactly once per client session (per AechronisDataFetcher's schedule).
     * Builds everything derivable purely from world.json's territory shapes —
     * chunk sets, core chunks, node borders/labels, and the precomputed occupied-
     * diagonal geometry. None of this depends on ownership, so it's never rebuilt
     * again after this single call, no matter how many towns.json polls happen
     * afterward.
     */
    public void loadWorldData(JsonObject world) {
        this.worldData = world;
        Map<String, String> icons = new HashMap<>();
        if (world.has("nodes") && world.get("nodes").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : world.getAsJsonObject("nodes").entrySet()) {
                JsonObject node = e.getValue().getAsJsonObject();
                if (node.has("icon") && !node.get("icon").isJsonNull()) {
                    icons.put(e.getKey().toLowerCase(java.util.Locale.ROOT), node.get("icon").getAsString());
                }
            }
        }
        this.nodeTypeIcons = Map.copyOf(icons);
        rebuildGeometry(world);
    }

    private void rebuildGeometry(JsonObject world) {
        JsonObject territories = world.has("territories") ?
                world.getAsJsonObject("territories") : new JsonObject();

        List<NodeBorderLine> newBorderLines = new ArrayList<>();
        Long2ObjectOpenHashMap<NodeLabelInfo> newLabelInfos = new Long2ObjectOpenHashMap<>();
        Map<String, Set<Long>> newTerritoryChunkMap = new HashMap<>();
        Map<String, Long> newCoreChunkMap = new HashMap<>();
        Map<String, List<NodeBorderLine>> newTerritoryDiagonals = new HashMap<>();

        for (Map.Entry<String, JsonElement> e : territories.entrySet()) {
            String tid = e.getKey();
            JsonObject territory = e.getValue().getAsJsonObject();
            JsonArray chunksFlat = territory.has("chunks") ? territory.getAsJsonArray("chunks") : new JsonArray();
            JsonArray coreChunkArr = territory.has("coreChunk") && !territory.get("coreChunk").isJsonNull()
                    ? territory.getAsJsonArray("coreChunk") : null;
            JsonArray nodes = territory.has("nodes") ? territory.getAsJsonArray("nodes") : new JsonArray();

            List<long[]> chunkPairs = new ArrayList<>();
            for (int i = 0; i + 1 < chunksFlat.size(); i += 2) {
                chunkPairs.add(new long[]{chunksFlat.get(i).getAsLong(), chunksFlat.get(i+1).getAsLong()});
            }

            Set<Long> chunkSet = new HashSet<>();
            for (long[] cp : chunkPairs) chunkSet.add(ChunkPos.asLong((int)cp[0], (int)cp[1]));
            newTerritoryChunkMap.put(tid, chunkSet);

            if (coreChunkArr != null) {
                newCoreChunkMap.put(tid, ChunkPos.asLong(
                        coreChunkArr.get(0).getAsInt(),
                        coreChunkArr.get(1).getAsInt()
                ));
            }

            // Corner-to-corner diagonal of the bounding box, clipped down to only the
            // segments that actually fall within the territory's real chunk set —
            // irregular (non-rectangular) territories would otherwise show a diagonal
            // overshooting into chunks the territory doesn't own. Ownership-independent,
            // computed for ALL territories regardless of capture state.
            if (!chunkPairs.isEmpty()) {
                int minCx = Integer.MAX_VALUE, minCz = Integer.MAX_VALUE;
                int maxCx = Integer.MIN_VALUE, maxCz = Integer.MIN_VALUE;
                for (long[] cp : chunkPairs) {
                    int cx = (int) cp[0], cz = (int) cp[1];
                    if (cx < minCx) minCx = cx;
                    if (cx > maxCx) maxCx = cx;
                    if (cz < minCz) minCz = cz;
                    if (cz > maxCz) maxCz = cz;
                }
                int x1 = minCx * 16, z1 = minCz * 16;
                int x2 = (maxCx + 1) * 16, z2 = (maxCz + 1) * 16;
                List<NodeBorderLine> clipped = clipDiagonalToShape(x1, z1, x2, z2, chunkSet);
                if (!clipped.isEmpty()) newTerritoryDiagonals.put(tid, clipped);
            }

            // Collect node type names. Border still renders for ANY node-bearing
            // territory (including basic-only) — unchanged. The LABEL, however, only
            // shows "specific resource" nodes: "basic" is filtered out entirely, and
            // a territory whose only node type is basic gets no label at all.
            List<String> nodeTypeNames = new ArrayList<>();
            for (JsonElement n : nodes) {
                if (!n.isJsonNull()) nodeTypeNames.add(n.getAsString());
            }

            // Borders for EVERY territory with chunks — live world.json has thousands with
            // an empty "nodes" list, and those were left unbordered before.
            if (!chunkPairs.isEmpty()) {
                List<int[]> borders = getBorderLines(chunkPairs);
                for (int[] line : borders) {
                    newBorderLines.add(new NodeBorderLine(line[0], line[1], line[2], line[3]));
                }
            }

            if (!chunkPairs.isEmpty() && !nodeTypeNames.isEmpty()) {
                // Label-only filtering: drop "basic", keep real resources in order.
                List<String> labelTypes = new ArrayList<>();
                for (String t : nodeTypeNames) {
                    if (!t.equalsIgnoreCase("basic")) labelTypes.add(t);
                }

                if (!labelTypes.isEmpty()) {
                    int labelX, labelZ;
                    if (territory.has("core") && !territory.get("core").isJsonNull()) {
                        JsonArray core = territory.getAsJsonArray("core");
                        labelX = core.get(0).getAsInt();
                        labelZ = core.get(1).getAsInt();
                    } else {
                        labelX = (int) chunkPairs.get(0)[0] * 16;
                        labelZ = (int) chunkPairs.get(0)[1] * 16;
                    }

                    StringBuilder label = new StringBuilder();
                    for (int i = 0; i < labelTypes.size(); i++) {
                        if (i > 0) label.append(", ");
                        label.append(capitalize(labelTypes.get(i)));
                    }

                    // Color = the FIRST resource's color (diamonds/gold/iron get their
                    // own; everything else, flint included, stays default white).
                    int labelColor = nodeLabelColor(labelTypes.get(0));

                    long textKey = ChunkPos.asLong(labelX >> 4, labelZ >> 4);
                    newLabelInfos.put(textKey, new NodeLabelInfo(label.toString(), labelX, labelZ, labelColor,
                            labelTypes.stream().map(t -> t.toLowerCase(java.util.Locale.ROOT)).toList()));
                }
            }
        }

        this.nodeBorderLines    = newBorderLines;
        this.nodeLabelInfos     = newLabelInfos;
        this.territoryChunkMap  = newTerritoryChunkMap;
        this.coreChunkMap       = newCoreChunkMap;
        this.territoryDiagonals = newTerritoryDiagonals;

        System.out.println("[Crusalis] Geometry built (once): " + newTerritoryChunkMap.size() +
                " territories, " + newBorderLines.size() + " node border lines, " +
                newLabelInfos.size() + " node labels.");
    }

    /**
     * Called every towns.json poll (every 60s). Ownership-only — does NOT touch
     * any world.json-derived geometry. Diffs the newly resolved color for every
     * currently-or-previously-owned territory against what was last applied, and
     * only mutates nationChunksRaw for territories whose color actually changed
     * (new owner, lost owner, or the owning nation's color itself changed). On a
     * quiet poll with zero ownership changes, this touches zero chunks.
     */
    public void loadTownsData(JsonObject towns, String rawJson) {
        // Build town -> nation from the authoritative nations[].towns lists, NOT from
        // individual residents' own "town" field — that field can go stale (e.g. a
        // player switches towns and the old town's residents/leader record never gets
        // updated), which silently breaks ownership resolution for that whole town.
        Map<String, String> townNation = new HashMap<>();
        JsonObject nationsForTowns = towns.has("nations") ? towns.getAsJsonObject("nations") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : nationsForTowns.entrySet()) {
            String nation = e.getKey();
            JsonObject nationObj = e.getValue().getAsJsonObject();
            if (nationObj.has("towns") && !nationObj.get("towns").isJsonNull()) {
                for (JsonElement townEl : nationObj.getAsJsonArray("towns")) {
                    if (!townEl.isJsonNull()) {
                        townNation.put(townEl.getAsString(), nation);
                    }
                }
            }
        }

        this.townNationMap = new HashMap<>(townNation);

        JsonObject townsObj = towns.has("towns") ? towns.getAsJsonObject("towns") : new JsonObject();
        // Ownership key per town: its nation, or the town itself when it has none (e.g. a
        // fresh map with no nations yet), so nationless towns still get filled in their
        // own town color instead of not rendering at all.
        Map<String, String> townOwner = new HashMap<>(townNation);
        for (String townName : townsObj.keySet()) townOwner.putIfAbsent(townName, townName);

        Map<String, String> territoryNation = new HashMap<>();
        Set<String> newCapturedFromJson = new HashSet<>();
        for (Map.Entry<String, JsonElement> e : townsObj.entrySet()) {
            String townName = e.getKey();
            JsonObject town = e.getValue().getAsJsonObject();
            String nation = townOwner.get(townName);
            if (town.has("captured") && !town.get("captured").isJsonNull()) {
                // Skip filler nations' captured lists entirely — Impassable "owns" and
                // "captures" a lot of terrain by design, and rendering diagonals on all
                // of it is pure noise. isFillerNation checks the OCCUPIER'S nation
                // (the one whose captured list this is), so real players capturing an
                // Impassable territory (occupier = real nation) will still show up.
                if (!isFillerNation(nation)) {
                    for (JsonElement tid : town.getAsJsonArray("captured")) {
                        if (!tid.isJsonNull()) newCapturedFromJson.add(tid.getAsString());
                    }
                }
            }
            if (nation == null) continue;
            // Pass 1 for this town: write territories + annexed only. The captured list
            // is deliberately deferred so it can be processed AFTER every town's baseline
            // ownership is set — see the second loop below.
            for (String field : new String[]{"territories", "annexed"}) {
                if (town.has(field)) {
                    for (JsonElement tid : town.getAsJsonArray(field)) {
                        if (!tid.isJsonNull()) territoryNation.put(tid.getAsString(), nation);
                    }
                }
            }
        }

        // Snapshot the pre-capture (defending) owner before pass 2 below overwrites
        // territoryNation with the occupier's nation. Needed to bootstrap territories
        // that are ALREADY captured the first time we ever see them (see the
        // lastTerritoryColor bootstrap in the ownership-diff loop further down) —
        // without this snapshot, that bootstrap would have no way to know who held the
        // territory before capture, only who currently occupies it.
        Map<String, String> baselineTerritoryNation = new HashMap<>(territoryNation);

        // Player username -> nation (see field javadoc above). Built from each town's
        // authoritative "residents" UUID roster, resolved to names via the top-level
        // "residents" object (uuid -> name). Deliberately NOT using each resident's own
        // "town"/"nation" self-report — same staleness reasoning as townNation above.
        Map<String, String> uuidToName = new HashMap<>();
        JsonObject residentsObj = towns.has("residents") ? towns.getAsJsonObject("residents") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : residentsObj.entrySet()) {
            JsonObject resident = e.getValue().getAsJsonObject();
            if (resident.has("name") && !resident.get("name").isJsonNull()) {
                uuidToName.put(e.getKey(), resident.get("name").getAsString());
            }
        }
        Map<String, String> newPlayerNationMap = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : townsObj.entrySet()) {
            String townName = e.getKey();
            JsonObject town = e.getValue().getAsJsonObject();
            String nation = townOwner.get(townName);
            if (nation == null || !town.has("residents") || town.get("residents").isJsonNull()) continue;
            for (JsonElement uuidEl : town.getAsJsonArray("residents")) {
                if (uuidEl.isJsonNull()) continue;
                String name = uuidToName.get(uuidEl.getAsString());
                if (name != null) newPlayerNationMap.put(name, nation);
            }
        }
        this.playerNationMap = newPlayerNationMap;

        // Pass 2: process every town's "captured" list AFTER all baseline ownership has
        // been written. This guarantees the occupier wins the color-resolution race, even
        // if a territory transiently appears in both the defender's baseline list and the
        // occupier's captured list during the plugin's own bookkeeping. Without this, the
        // final territoryNation for that tid is whichever town happened to be iterated
        // last — nondeterministic across HashMap iteration orders, and we saw this cause
        // occupied diagonals to render in the defender's color instead of the occupier's.
        for (Map.Entry<String, JsonElement> e : townsObj.entrySet()) {
            String townName = e.getKey();
            JsonObject town = e.getValue().getAsJsonObject();
            String nation = townOwner.get(townName);
            if (nation == null) continue;
            if (town.has("captured") && !town.get("captured").isJsonNull()) {
                for (JsonElement tid : town.getAsJsonArray("captured")) {
                    if (!tid.isJsonNull()) territoryNation.put(tid.getAsString(), nation);
                }
            }
        }

        // Same-nation "capture": occupier and pre-capture owner resolve to the same
        // nation (one town recapturing/holding territory from another town in its own
        // nation, or a stale flag the plugin never cleared after an intra-nation
        // recapture). We color and diagonal by NATION, not by individual town, so this
        // isn't a real cross-nation war event — both "sides" are the same color. Drop
        // these from the occupied set entirely rather than render a same-color diagonal
        // that flags nothing. territoryNation here already holds the OCCUPIER's nation
        // (just overwritten above); baselineTerritoryNation still holds the pre-capture
        // nation.
        newCapturedFromJson.removeIf(tid -> {
            String occupierNation = territoryNation.get(tid);
            String ownerNation = baselineTerritoryNation.get(tid);
            return occupierNation != null && occupierNation.equals(ownerNation);
        });

        // Nation colors read directly from towns.json's authoritative "nations" object —
        // cheap, proportional to nation count (low dozens), not territory/chunk count.
        Map<String, Integer> newNationColors = new HashMap<>();
        JsonObject nationsObj = towns.has("nations") ? towns.getAsJsonObject("nations") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : nationsObj.entrySet()) {
            String nation = e.getKey();
            JsonObject nationObj = e.getValue().getAsJsonObject();
            if (nationObj.has("color")) {
                JsonElement colorEl = nationObj.get("color");
                if (!colorEl.isJsonNull()) {
                    JsonArray c = colorEl.getAsJsonArray();
                    if (c.size() >= 3) {
                        newNationColors.put(nation, rgb(c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt()));
                    }
                }
            }
        }
        // Nationless towns are keyed by town name (see townOwner) and use their own color.
        for (Map.Entry<String, JsonElement> e : townsObj.entrySet()) {
            if (townNation.containsKey(e.getKey())) continue;
            JsonElement colorEl = e.getValue().getAsJsonObject().get("color");
            if (colorEl != null && colorEl.isJsonArray() && colorEl.getAsJsonArray().size() >= 3) {
                JsonArray c = colorEl.getAsJsonArray();
                newNationColors.putIfAbsent(e.getKey(), rgb(c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt()));
            }
        }
        newNationColors.putAll(gistColors);
        this.nationColors = newNationColors;

        // Town-derived waypoints/labels — cheap (proportional to town count), recomputed
        // every poll for simplicity; not worth diffing, this loop never touches chunks.
        List<TownWaypoint> newWaypoints = new ArrayList<>();
        Long2ObjectOpenHashMap<NodeLabelInfo> newTownLabelInfos = new Long2ObjectOpenHashMap<>();
        Map<String, int[]> townSpawnXZ = new HashMap<>(); // townName -> {x, z}, for capital lookup
        for (Map.Entry<String, JsonElement> e : townsObj.entrySet()) {
            String townName = e.getKey();
            JsonObject town = e.getValue().getAsJsonObject();
            String nation = townNation.getOrDefault(townName, "");
            if (town.has("spawn") && !town.get("spawn").isJsonNull()) {
                JsonArray spawn = town.getAsJsonArray("spawn");
                if (spawn.size() >= 3) {
                    String label = nation.isEmpty() ? townName : townName + " | " + nation;
                    int spawnX = spawn.get(0).getAsInt();
                    int spawnZ = spawn.get(2).getAsInt();
                    newWaypoints.add(new TownWaypoint(label, spawnX, spawn.get(1).getAsInt(), spawnZ));

                    long townTextKey = ChunkPos.asLong(spawnX >> 4, spawnZ >> 4);
                    newTownLabelInfos.put(townTextKey, new NodeLabelInfo(townName, spawnX, spawnZ));
                    townSpawnXZ.put(townName, new int[]{spawnX, spawnZ});
                }
            }
        }
        this.townWaypoints  = newWaypoints;
        this.townLabelInfos = newTownLabelInfos;

        // Nation labels — at each nation's CAPITAL town spawn, lifted a small fixed
        // offset north (−Z) so it floats above the town label rather than overlapping.
        // Filler nations are skipped. Uses the nation key name as the label text
        // (longName in the data can be the literal string "null", so it's unreliable).
        List<NationLabelInfo> newNationLabels = new ArrayList<>();
        // Nation labels render at 0.9 scale (see AechronisRenderer.NATION_LABEL_SCALE)
        // vs. the town label's 0.5 at the same X — needs more clearance than a flat
        // 2-chunk gap to avoid the two overlapping.
        final int NATION_LABEL_OFFSET_Z = -80; // 5 chunks north
        for (Map.Entry<String, JsonElement> e : nationsObj.entrySet()) {
            String nationName = e.getKey();
            if (isFillerNation(nationName)) continue;
            JsonObject nationObj = e.getValue().getAsJsonObject();
            String capital = (nationObj.has("capital") && !nationObj.get("capital").isJsonNull())
                    ? nationObj.get("capital").getAsString() : null;
            if (capital == null) continue;
            int[] xz = townSpawnXZ.get(capital);
            if (xz == null) continue; // capital town has no known spawn — skip rather than guess
            int color = newNationColors.getOrDefault(nationName, rgb(255, 255, 255));
            newNationLabels.add(new NationLabelInfo(nationName, xz[0], xz[1] + NATION_LABEL_OFFSET_Z, color));
        }
        this.nationLabelInfos = newNationLabels;

        if (territoryChunkMap.isEmpty()) {
            // Defensive only — should never happen in practice, since the fetcher's
            // single-thread scheduler guarantees loadWorldData() already ran before any
            // loadTownsData() call. Kept in case the fetch order ever changes.
            System.out.println("[Crusalis] Geometry not built yet, skipping ownership diff this poll.");
            return;
        }

        // ---- Ownership diff: only touch territories whose resolved color changed ----
        Map<String, Integer> newDiagonalColors = new HashMap<>(); // tid -> color, for ALL owned territories (cheap)
        Map<String, Integer> newResolvedColor  = new HashMap<>(); // tid -> color, for diffing against lastTerritoryColor
        for (Map.Entry<String, String> e : territoryNation.entrySet()) {
            String tid = e.getKey();
            String nation = e.getValue();
            int color = newNationColors.getOrDefault(nation, rgb(200, 200, 200));
            newDiagonalColors.put(tid, color);
            newResolvedColor.put(tid, color);
        }

        Set<String> allKnownTids = new HashSet<>(lastTerritoryColor.keySet());
        allKnownTids.addAll(newResolvedColor.keySet());
        // Also include any chat-flipped territories, so the grace-expiry logic below always
        // gets a chance to reconcile and clear their stamps even if they aren't currently
        // present in the JSON ownership (prevents a stale optimistic color or a leaked stamp).
        allKnownTids.addAll(chatFlipTimestamps.keySet());

        long now = System.currentTimeMillis();
        int changedCount = 0;
        int skippedGrace = 0;
        int skippedOccupied = 0;
        for (String tid : allKnownTids) {
            // Two-phase capture/annex model: territories currently in the occupied set
            // must KEEP the losing nation's base color on the map until they're annexed.
            // Skip them here — the ownership diff would otherwise flip them to the new
            // owner's color the moment the JSON puts them in the occupier's town.
            // The captured→annexed transition detection block below handles the flip.
            if (newCapturedFromJson.contains(tid)) {
                // Bootstrap exception: territories that are ALREADY captured the very
                // first time we ever see them (e.g. "Warzone" territories that cycle
                // between occupiers and are essentially never NOT captured) never get a
                // base color from a prior poll's pre-capture state — lastTerritoryColor
                // has no entry for them, so skipping unconditionally here would leave
                // their chunk fill blank forever, with only the occupied diagonal ever
                // rendering. Apply the pre-capture (defending) owner's color once; every
                // later poll finds lastTerritoryColor already populated and falls through
                // to the normal skip below, same as any other occupied territory.
                if (!lastTerritoryColor.containsKey(tid)) {
                    String baselineNation = baselineTerritoryNation.get(tid);
                    if (baselineNation != null) {
                        int bootstrapColor = newNationColors.getOrDefault(baselineNation, rgb(200, 200, 200));
                        applyTerritoryColor(tid, bootstrapColor);
                        lastTerritoryColor.put(tid, bootstrapColor);
                        changedCount++;
                    }
                }
                skippedOccupied++;
                continue;
            }
            Integer prevColor = lastTerritoryColor.get(tid);
            Integer newColor  = newResolvedColor.get(tid); // null = no longer owned by anyone resolvable

            // Grace-window handling, evaluated independently of the color diff below.
            // A territory that was chat-flipped holds an optimistic on-screen color that
            // lastTerritoryColor does NOT reflect (we never updated it at flip time), so
            // we can't rely on the prev-vs-new diff alone to reconcile it.
            Long flippedAt = chatFlipTimestamps.get(tid);
            boolean graceExpiring = false;
            if (flippedAt != null) {
                if (now - flippedAt < CHAT_FLIP_GRACE_MS) {
                    // Still in grace — trust the chat flip; leave the optimistic color and
                    // lastTerritoryColor untouched. Skip this territory entirely this poll.
                    skippedGrace++;
                    continue;
                } else {
                    // Window expired — the poll now resumes authority. Clear the stamp and
                    // FORCE a reconcile this poll even if prevColor == newColor, because the
                    // on-screen color may be a now-stale optimistic flip that lastTerritoryColor
                    // never recorded (e.g. a chat flip that the server never actually confirmed).
                    chatFlipTimestamps.remove(tid);
                    graceExpiring = true;
                }
            }

            if (graceExpiring || !Objects.equals(prevColor, newColor)) {
                applyTerritoryColor(tid, newColor);
                changedCount++;
                if (newColor != null) lastTerritoryColor.put(tid, newColor);
                else lastTerritoryColor.remove(tid);
            }
        }

        // ---- Detect captured -> annexed transitions ----
        // Any territory that WAS in capturedTerritoryIds but is NOT in the fresh
        // newCapturedFromJson set has left the "occupied" state — either annexed by
        // the occupier (/t annex succeeded), or liberated back to the original owner,
        // or its owning town was disbanded, etc. In all these cases we call
        // annexTerritory() to flip the base color to the (now-resolved) owner and
        // remove the occupied marker/diagonal.
        //
        // We skip territories still inside the chat-flip grace window — towns.json may
        // not have caught up yet, and reacting prematurely would flicker the diagonal
        // (removed by poll -> re-added by next poll once server catches up).
        long nowForAnnex = System.currentTimeMillis();
        for (String tid : new HashSet<>(capturedTerritoryIds)) {
            if (newCapturedFromJson.contains(tid)) continue; // still occupied, no transition
            Long flippedAt = chatFlipTimestamps.get(tid);
            if (flippedAt != null && (nowForAnnex - flippedAt) < CHAT_FLIP_GRACE_MS) continue;
            // Left the captured set — resolve the new/current owner and flip base color.
            Integer resolvedColor = newResolvedColor.get(tid);
            annexTerritory(tid, resolvedColor);
            // Keep lastTerritoryColor in sync with the flip we just performed, so the
            // ownership diff loop above (which already ran this poll) doesn't fight us
            // next poll. Setting it here means next poll's diff sees "same as last" and
            // leaves it alone.
            if (resolvedColor != null) lastTerritoryColor.put(tid, resolvedColor);
        }

        // Captured/occupied set + colors — JSON poll is authoritative. retainAll+addAll
        // (instead of clear()+addAll) avoids a window where the set is briefly empty
        // while the renderer might be reading it on another thread.
        // NOTE: territories held by the grace window above intentionally survive this
        // retainAll, because they're STILL in capturedTerritoryIds after the loop
        // above (grace-window skip continue'd without touching them). If the server
        // has NOT caught up, JSON still shows them as captured too, so newCapturedFromJson
        // contains them → retainAll leaves them alone → no flicker. If the server HAS
        // caught up mid-grace, the grace window will expire naturally on a later poll
        // and the transition detection will fire correctly then.
        // Territories still inside their chat-flip grace window are protected from eviction
        // even if the JSON hasn't caught up yet; otherwise a poll before the server updates
        // would clear a fresh chat-driven capture's diagonal. Atomic w.r.t. capture/annex.
        boolean occupiedSetChanged;
        synchronized (occupiedStateLock) {
            long graceNow = System.currentTimeMillis();
            Set<String> protectedFromEviction = new HashSet<>(newCapturedFromJson);
            for (Map.Entry<String, Long> entry : chatFlipTimestamps.entrySet()) {
                if (graceNow - entry.getValue() < CHAT_FLIP_GRACE_MS) protectedFromEviction.add(entry.getKey());
            }
            occupiedSetChanged = !this.capturedTerritoryIds.equals(protectedFromEviction);
            this.capturedTerritoryIds.retainAll(protectedFromEviction);
            this.capturedTerritoryIds.addAll(newCapturedFromJson);
            this.territoryDiagonalColors.keySet().retainAll(protectedFromEviction);
            for (String tid : newCapturedFromJson) {
                Integer color = newDiagonalColors.get(tid);
                if (color != null) this.territoryDiagonalColors.put(tid, color);
            }
        }
        if (occupiedSetChanged && rawJson != null) {
            AechronisWarCapture.snapshotTownsJson(rawJson, "occupied-set-changed");
        }

        if (changedCount > 0) {
            this.dirty = true;
        }

        String pollSummary = "Towns poll: " + changedCount + " territories changed ownership/color, " +
                skippedGrace + " held by chat-flip grace, " +
                skippedOccupied + " held as occupied (two-phase), " +
                newCapturedFromJson.size() + " captured/occupied territories.";
        System.out.println("[Crusalis] " + pollSummary);
        AechronisWarCapture.logState(pollSummary); // no-op unless AechronisWarCapture.ENABLED
    }

    /** Applies (or removes) a territory's resolved color to its actual chunks.
     *  colorOrNull == null means the territory is no longer owned by anyone resolvable
     *  — its chunks are removed from the fill map entirely. The core chunk always
     *  renders as CORE_CHUNK_SENTINEL when owned, matching prior behavior. */
    private void applyTerritoryColor(String tid, Integer colorOrNull) {
        Set<Long> chunks = territoryChunkMap.get(tid);
        if (chunks == null) return; // geometry has no record of this territory id — nothing to color
        Long corePos = coreChunkMap.get(tid);
        synchronized (nationChunksLock) {
            if (colorOrNull == null) {
                for (long pos : chunks) nationChunksRaw.remove(pos);
            } else {
                int color = colorOrNull;
                for (long pos : chunks) {
                    if (corePos != null && pos == corePos) {
                        nationChunksRaw.put(pos, (long) CORE_CHUNK_SENTINEL);
                    } else {
                        nationChunksRaw.put(pos, (long) color);
                    }
                }
            }
        }
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    /** Nations that are map filler rather than real player nations — skipped for
     *  nation labels. Matched case-insensitively. */
    private static boolean isFillerNation(String nationName) {
        if (nationName == null) return true;
        String n = nationName.trim().toLowerCase();
        return n.equals("impassable") || n.equals("wilderness");
    }

    /** RGB (no alpha) for a node label, by its first resource type. Only diamonds,
     *  gold, and iron get a distinct color; everything else (coal, wheat, animals,
     *  flint, etc.) stays default white. Matched case-insensitively against the real
     *  world.json strings (note: "diamonds" is plural in the data). */
    private static int nodeLabelColor(String firstResource) {
        switch (firstResource.toLowerCase()) {
            case "diamonds": return 0x00FFFF; // cyan / aqua
            case "gold":     return 0xFFFF00; // yellow
            case "iron":     return 0xAAAAAA; // grey
            default:         return 0xFFFFFF; // white
        }
    }

    /**
     * Chat-triggered by `[War] X captured territory (id=Y)` (also reused for the
     * `liberated territory` message per existing convention).
     *
     * NEW BEHAVIOR (two-phase capture/annex model): capture is the "go between" state.
     * The territory's base color is NOT changed here — the losing nation's color stays
     * on the map so you can see who originally owned it. Only two things happen:
     *   1. The territory is marked as occupied (added to capturedTerritoryIds).
     *   2. The occupying nation's color is recorded (territoryDiagonalColors) so the
     *      occupied-diagonal render feature can draw a diagonal in that color across
     *      the whole node.
     *
     * The full base-color flip only happens later, when annexTerritory() is called —
     * which is triggered by the towns.json poll detecting the territory has moved from
     * a town's "captured" list to its "annexed" list (i.e. the occupier ran /t annex).
     * There is no chat trigger for annex yet.
     *
     * capturingPlayerName is the acting player's USERNAME, not a town — confirmed
     * against the plugin source (FlagWar.kt broadcasts `[War] ${attacker?.name} captured
     * territory ...` where attacker is a Resident). Resolved to a nation via
     * playerNationMap (built in loadTownsData()), not townNationMap.
     */
    public void captureTerritory(String tid, String capturingPlayerName) {
        Set<Long> chunks = territoryChunkMap.get(tid);
        if (chunks == null) {
            System.out.println("[Crusalis] captureTerritory: no chunks found for tid=" + tid);
            return;
        }
        ResolvedNation resolved = resolvePlayerNation(capturingPlayerName, rgb(200, 200, 200));
        String nation = resolved.nation();
        int color = resolved.color();

        // Stamp the chat-flip time so the ownership diff in loadTownsData() will NOT
        // remove this optimistic occupied-state marker for CHAT_FLIP_GRACE_MS — towns.json
        // may not have regenerated to reflect the capture yet, and dropping it from
        // capturedTerritoryIds prematurely would cause a visible flicker of the diagonal
        // (appear -> briefly disappear -> reappear once the server catches up).
        synchronized (occupiedStateLock) {
            chatFlipTimestamps.put(tid, System.currentTimeMillis());
            capturedTerritoryIds.add(tid);
            territoryDiagonalColors.put(tid, color);
        }

        String summary = "captureTerritory: marked tid=" + tid + " occupied by " +
                (nation != null ? nation : capturingPlayerName) + " (base color unchanged; diagonal color set)";
        System.out.println("[Crusalis] " + summary);
        AechronisWarCapture.logState(summary); // no-op unless AechronisWarCapture.ENABLED
    }

    /**
     * Chat-triggered by `[War] X liberated territory (id=Y)`. Per the plugin source
     * (FlagWar.kt), this message fires specifically when the ORIGINAL owner (or an
     * ally/nation-mate) reclaims their own territory from an occupier — the plugin
     * clears the occupier entirely at that point (Nodes.releaseTerritory()), it's the
     * end of the war for this node, not a new capture.
     *
     * Previously this reused captureTerritory(), which marks the territory OCCUPIED —
     * so every successful defense instantly painted a same-color diagonal (liberating
     * player's own nation, since they're reclaiming their own land) that lingered for
     * the full chat-flip grace window until the next poll cleaned it up. Liberation
     * should immediately clear the occupied state and flip the base color to the
     * liberating player's nation, mirroring what annexTerritory() already does for the
     * JSON-poll-detected transition — so just resolve the color and delegate to it.
     */
    public void liberateTerritory(String tid, String liberatingPlayerName) {
        ResolvedNation resolved = resolvePlayerNation(liberatingPlayerName, rgb(200, 200, 200));
        String nation = resolved.nation();
        int color = resolved.color();

        annexTerritory(tid, color);
        // Keep lastTerritoryColor in sync so the next poll's ownership diff sees
        // "same as last" and doesn't redundantly reapply/log a color change.
        lastTerritoryColor.put(tid, color);

        String summary = "liberateTerritory: tid=" + tid + " restored to " +
                (nation != null ? nation : liberatingPlayerName) + " (occupied marker cleared)";
        System.out.println("[Crusalis] " + summary);
        AechronisWarCapture.logState(summary); // no-op unless AechronisWarCapture.ENABLED
    }

    /**
     * Triggered by the towns.json poll when a territory moves out of a town's "captured"
     * list (typically into "annexed" — the occupier ran /t annex). No confirmed chat
     * string for this yet, so the poll is the only trigger.
     *
     * This is where the ACTUAL base color flip happens under the two-phase model.
     * captureTerritory() only marks occupied state (with a diagonal); this method is
     * what recolors the underlying node to the new owner's color and removes the
     * occupied marker.
     *
     * If the caller doesn't know the new owner's color yet (e.g. an "annexed" state
     * transition with no resolvable nation), pass null and this method will only
     * clear the occupied state without recoloring — the next ownership diff pass will
     * do the recolor when it detects the color change.
     *
     * Sets mapData.dirty when it actually recolors chunks — this method is the only
     * writer to nationChunksRaw reachable from a chat event (via liberateTerritory()),
     * and unlike the loadTownsData() ownership-diff loop (which sets dirty itself),
     * nothing else invalidates the renderer's alpha-cache for a chat-triggered
     * liberation. Without this, liberateTerritory() also updates lastTerritoryColor to
     * match, so the *next* towns.json poll sees "no change" for this territory too —
     * the recolor could otherwise never actually reach the screen.
     */
    public void annexTerritory(String tid, Integer newOwnerColor) {
        AechronisWarCapture.logState("annexTerritory: tid=" + tid + " newOwnerColor=" +
                (newOwnerColor != null ? Integer.toHexString(newOwnerColor) : "null")); // no-op unless ENABLED
        synchronized (occupiedStateLock) {
            capturedTerritoryIds.remove(tid);
            territoryDiagonalColors.remove(tid);
            chatFlipTimestamps.remove(tid);
        }
        if (newOwnerColor == null) return;
        Set<Long> chunks = territoryChunkMap.get(tid);
        if (chunks == null) return;
        Long corePos = coreChunkMap.get(tid);
        synchronized (nationChunksLock) {
            for (long pos : chunks) {
                if (corePos != null && pos == corePos) {
                    nationChunksRaw.put(pos, (long) CORE_CHUNK_SENTINEL);
                } else {
                    nationChunksRaw.put(pos, (long) (int) newOwnerColor);
                }
            }
        }
        this.dirty = true;
    }


    /** Resolves a player's nation and that nation's configured color via
     *  playerNationMap, falling back to `fallback` for the color when the player can't
     *  be resolved to a nation or the nation has no configured color. Centralizes the
     *  lookup-with-fallback pattern shared by every chat-driven capture/attack event
     *  above (captureTerritory, liberateTerritory). */
    private ResolvedNation resolvePlayerNation(String playerName, int fallback) {
        String nation = playerNationMap.get(playerName);
        int color = nation != null ? nationColors.getOrDefault(nation, fallback) : fallback;
        return new ResolvedNation(nation, color);
    }

    private record ResolvedNation(String nation, int color) {}

    /**
     * Builds a fresh alpha-applied snapshot of the nation-fill chunk colors, safely
     * w.r.t. concurrent writers (the towns.json poll diff, and chat-driven capture
     * events). Uses fastutil's primitive entry-set iteration — no boxing of the
     * underlying long keys/values, unlike a plain Map.Entry<Long,Long> loop. Called
     * by the renderer only when mapData.dirty is true or the alpha config changed,
     * not every frame.
     */
    public Long2LongOpenHashMap buildAlphaCache(int alpha) {
        Long2LongOpenHashMap result;
        synchronized (nationChunksLock) {
            result = new Long2LongOpenHashMap(nationChunksRaw.size());
            for (Long2LongMap.Entry e : nationChunksRaw.long2LongEntrySet()) {
                int rgb = (int) e.getLongValue();
                result.put(e.getLongKey(), (long) withAlpha(rgb, alpha));
            }
        }
        return result;
    }

    public static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    private List<int[]> getBorderLines(List<long[]> chunkPairs) {
        Set<Long> chunkSet = new HashSet<>();
        for (long[] cp : chunkPairs) chunkSet.add(ChunkPos.asLong((int)cp[0], (int)cp[1]));

        Set<Long> hEdges = new HashSet<>();
        Set<Long> vEdges = new HashSet<>();

        for (long[] cp : chunkPairs) {
            int cx = (int)cp[0], cz = (int)cp[1];
            if (!chunkSet.contains(ChunkPos.asLong(cx, cz-1))) hEdges.add(ChunkPos.asLong(cx, cz));
            if (!chunkSet.contains(ChunkPos.asLong(cx, cz+1))) hEdges.add(ChunkPos.asLong(cx, cz+1));
            if (!chunkSet.contains(ChunkPos.asLong(cx-1, cz))) vEdges.add(ChunkPos.asLong(cx, cz));
            if (!chunkSet.contains(ChunkPos.asLong(cx+1, cz))) vEdges.add(ChunkPos.asLong(cx+1, cz));
        }

        List<int[]> lines = new ArrayList<>();
        Set<Long> used = new HashSet<>();

        for (long edge : hEdges) {
            if (used.contains(edge)) continue;
            int cx = ChunkPos.getX(edge), cz = ChunkPos.getZ(edge);
            int endX = cx;
            while (hEdges.contains(ChunkPos.asLong(endX+1, cz)) && !used.contains(ChunkPos.asLong(endX+1, cz))) endX++;
            for (int x = cx; x <= endX; x++) used.add(ChunkPos.asLong(x, cz));
            lines.add(new int[]{cx*16, cz*16, (endX+1)*16, cz*16});
        }

        used.clear();
        for (long edge : vEdges) {
            if (used.contains(edge)) continue;
            int cx = ChunkPos.getX(edge), cz = ChunkPos.getZ(edge);
            int endZ = cz;
            while (vEdges.contains(ChunkPos.asLong(cx, endZ+1)) && !used.contains(ChunkPos.asLong(cx, endZ+1))) endZ++;
            for (int z = cz; z <= endZ; z++) used.add(ChunkPos.asLong(cx, z));
            lines.add(new int[]{cx*16, cz*16, cx*16, (endZ+1)*16});
        }

        return lines;
    }

    // Walks the bounding-box diagonal from (x1,z1) to (x2,z2) and keeps only the
    // contiguous runs whose sampled points fall inside chunkSet, so irregularly
    // shaped territories don't get a diagonal overshooting into unowned chunks.
    // Sampled every 2 blocks — finer than the 16-block chunk grid, so no chunk
    // crossing along the line can be skipped.
    private List<NodeBorderLine> clipDiagonalToShape(int x1, int z1, int x2, int z2, Set<Long> chunkSet) {
        List<NodeBorderLine> segments = new ArrayList<>();
        int dx = x2 - x1, dz = z2 - z1;
        double length = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (length == 0) return segments;

        int steps = (int) Math.ceil(length / 2.0);
        int segStartX = 0, segStartZ = 0;
        int lastX = x1, lastZ = z1;
        boolean wasInside = false;

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int px = (int) Math.round(x1 + t * dx);
            int pz = (int) Math.round(z1 + t * dz);
            boolean inside = chunkSet.contains(ChunkPos.asLong(px >> 4, pz >> 4));

            if (inside && !wasInside) {
                segStartX = px;
                segStartZ = pz;
            } else if (!inside && wasInside) {
                segments.add(new NodeBorderLine(segStartX, segStartZ, lastX, lastZ));
            }
            wasInside = inside;
            lastX = px;
            lastZ = pz;
        }
        if (wasInside) segments.add(new NodeBorderLine(segStartX, segStartZ, lastX, lastZ));

        return segments;
    }

    // ---- Inner classes ----



    public static class TownWaypoint {
        public final String label;
        public final int x, y, z;
        public TownWaypoint(String label, int x, int y, int z) {
            this.label = label; this.x = x; this.y = y; this.z = z;
        }
    }

    /** Raw node border line — uniform color decided entirely by the renderer.
     *  Also reused for territory diagonals (corner-to-corner bounding-box line). */
    public static class NodeBorderLine {
        public final int x1, z1, x2, z2;
        public NodeBorderLine(int x1, int z1, int x2, int z2) {
            this.x1 = x1; this.z1 = z1; this.x2 = x2; this.z2 = z2;
        }
    }

    /** Raw node label data — plain text plus an RGB color (no alpha; the renderer
     *  applies alpha). The 3-arg constructor defaults to white, used by town labels
     *  which are always white; node labels use the 4-arg form to carry resource color. */
    public static class NodeLabelInfo {
        public final String label;
        public final int x, z;
        public final int color; // RGB, no alpha
        /** Node types in label order, lower case (e.g. "wheat", "ultra-mega-node"); empty for towns. */
        public final List<String> resources;
        public NodeLabelInfo(String label, int x, int z) {
            this(label, x, z, 0xFFFFFF, List.of());
        }
        public NodeLabelInfo(String label, int x, int z, int color, List<String> resources) {
            this.label = label; this.x = x; this.z = z; this.color = color; this.resources = resources;
        }
    }

    /** Nation label — name text + position (already offset) + RGB color (nation color). */
    public static class NationLabelInfo {
        public final String label;
        public final int x, z;
        public final int color; // RGB, no alpha
        public NationLabelInfo(String label, int x, int z, int color) {
            this.label = label; this.x = x; this.z = z; this.color = color;
        }
    }

    public static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    public static int argb(int r, int g, int b) {
        return (0x63 << 24) | (r << 16) | (g << 8) | b;
    }

    public static int argbFull(int r, int g, int b) {
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }
}