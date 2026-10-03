package com.dcfiendish.aechronismapmod;

import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the Crusalis overlay straight into Xaero's World Map and Minimap — no XaeroPlus.
 *
 * Geometry (fills and lines) goes into Xaero's own map framebuffers through
 * AechronisWorldMapMixin / AechronisMinimapMixin, so it zooms, rotates and gets clipped
 * exactly like the map tiles. Labels are drawn later, after the map is composited
 * (AechronisWorldMapMixin / AechronisMinimapLabelMixin), so they stay upright and crisp on a
 * rotating minimap. Hook details: docs/xaero-hooks.md.
 *
 * Everything drawn is built on the client tick (tick()) into immutable lists that the render
 * thread only reads; static layers rebuild only when map data or config change.
 */
public final class AechronisRenderer {
    private static final int DEFAULT_NODE_COLOR = 0x000000;
    // Every overlay element except the nation fill renders fully opaque, always —
    // opacity is only user-adjustable for the nation fill (see AechronisConfig).
    private static final int FULL_ALPHA = 255;

    // Line width in config units, as in the XaeroPlus days: 16 * width blocks wide, but never
    // thinner than 1.6 framebuffer pixels.
    private static final float NODE_BORDER_WIDTH = 0.1f;

    private static final float NODE_LABEL_SCALE = 0.5f;
    private static final float NATION_LABEL_SCALE = 0.9f;

    record Rect(int x1, int z1, int x2, int z2, int argb) {}
    record Seg(int x1, int z1, int x2, int z2, int argb, float width) {}
    /**
     * text may be null (icons only); icons are drawn as a row centred on the anchor, text below.
     * maxHalfView: the label hides on the world map once the view's half-extent (blocks)
     * exceeds this, so zoomed-out maps don't drown in text.
     */
    record Label(String text, int x, int z, int argb, float scale, List<AechronisIcons.Icon> icons,
                 double maxHalfView) {}

    // Zoom decluttering (world map), as in the XaeroPlus-era Aechronis mod: resource node
    // markers go past ~6 regions of view radius, town labels past ~28; nation names stay.
    private static final double NODE_LABEL_MAX_HALF_VIEW = 6 * 512;
    private static final double TOWN_LABEL_MAX_HALF_VIEW = 28 * 512;

    /** Everything one frame draws, in draw order. Swapped atomically, never mutated. */
    /**
     * Node borders bucketed by 512-block region of their first point, so a frame only walks
     * the regions in view instead of all ~277k segments (every frame, on both maps).
     */
    record Buckets(Long2ObjectOpenHashMap<List<Seg>> byRegion, int maxLength) {
        static final Buckets EMPTY = new Buckets(new Long2ObjectOpenHashMap<>(), 0);
        static final int SHIFT = 9;

        static Buckets of(List<Seg> segs) {
            Long2ObjectOpenHashMap<List<Seg>> map = new Long2ObjectOpenHashMap<>();
            int max = 0;
            for (Seg l : segs) {
                map.computeIfAbsent(ChunkPos.asLong(l.x1 >> SHIFT, l.z1 >> SHIFT), k -> new ArrayList<>()).add(l);
                max = Math.max(max, Math.max(Math.abs(l.x2 - l.x1), Math.abs(l.z2 - l.z1)));
            }
            return new Buckets(map, max);
        }
    }

    private record Scene(List<Rect> nationFills, Buckets nodeBorders, List<Seg> occupiedDiagonals,
                         List<Label> labels,
                         int gridArgb, int gridWidthPx, int iconSize, float hideBordersBelow) {
        static final Scene EMPTY = new Scene(List.of(), Buckets.EMPTY, List.of(), List.of(),
                0, 0, 0, 0);
    }

    /** The config values the static layers depend on; a change triggers a rebuild. */
    private record StaticConfig(boolean everything, boolean fills, int fillAlpha, boolean borders, boolean white,
                                boolean nodeLabels, boolean townLabels, boolean nationLabels, boolean icons,
                                String filter) {
        static StaticConfig of(AechronisConfig c) {
            return new StaticConfig(c.showEverything, c.showNationFills, c.getNationFillAlpha(), c.showNodeBorders,
                    c.whiteBorders, c.showNodeLabels, c.showTownLabels, c.showNationLabels, c.showIcons,
                    c.resourceFilter.trim().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private static AechronisMapData mapData;
    private static volatile boolean active;
    private static volatile Scene scene = Scene.EMPTY;

    // Static layers, rebuilt only on data/config change.
    private static StaticConfig lastConfig;
    private static int lastBorderCount = -1, lastNodeLabelCount = -1, lastTownLabelCount = -1,
            lastNationLabelCount = -1;
    private static List<Rect> nationFills = List.of();
    private static Buckets nodeBorders = Buckets.EMPTY;
    private static List<Label> labels = List.of();

    private AechronisRenderer() {}

    public static void init(AechronisMapData data) {
        mapData = data;
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean on) {
        active = on;
        if (!on) scene = Scene.EMPTY;
    }

    // Crusalis nodes/towns/nations only exist in the Overworld. Compare the map being
    // drawn against the Overworld, not the player's current dimension: otherwise standing
    // in the Nether draws Overworld data onto the Nether map, and viewing the Overworld
    // map from the Nether shows nothing.
    public static boolean isCrusalisDimension(ResourceKey<Level> dimension) {
        return dimension == Level.OVERWORLD;
    }

    private static Scene sceneFor(ResourceKey<Level> mapDimension) {
        return active && isCrusalisDimension(mapDimension) ? scene : Scene.EMPTY;
    }

    // ---- Client tick: purge + cache rebuilds ----

    /** Runs every client tick: cache rebuilds while the overlay is active. */
    public static void tick() {
        if (mapData == null || !active) return;

        AechronisConfig cfg = AechronisConfig.get();
        rebuildStaticIfNeeded(cfg);
        scene = buildScene(cfg);
    }

    /** Node types present in the data, sorted, for the resource filter. */
    public static List<String> resourceTypes() {
        if (mapData == null) return List.of();
        java.util.TreeSet<String> types = new java.util.TreeSet<>();
        for (AechronisMapData.NodeLabelInfo i : mapData.nodeLabelInfos.values()) types.addAll(i.resources);
        return List.copyOf(types);
    }

    private static List<AechronisIcons.Icon> icons(boolean show, List<String> nodeTypes) {
        if (!show) return List.of();
        List<AechronisIcons.Icon> out = new ArrayList<>();
        for (String type : nodeTypes) {
            AechronisIcons.Icon icon = AechronisIcons.forNodeType(type);
            if (icon != null && !out.contains(icon)) out.add(icon);
        }
        return List.copyOf(out);
    }

    private static List<AechronisIcons.Icon> icon(boolean show, String name) {
        AechronisIcons.Icon icon = show ? AechronisIcons.get(name) : null;
        return icon == null ? List.of() : List.of(icon);
    }

    /** Re-resolves icons on the next tick (after icons reload or new node types arrive). */
    public static void invalidate() {
        lastConfig = null;
    }

    private static void rebuildStaticIfNeeded(AechronisConfig cfg) {
        StaticConfig sc = StaticConfig.of(cfg);
        boolean dirty = mapData.dirty;
        if (!dirty && sc.equals(lastConfig)
                && mapData.nodeBorderLines.size() == lastBorderCount
                && mapData.nodeLabelInfos.size() == lastNodeLabelCount
                && mapData.townLabelInfos.size() == lastTownLabelCount
                && mapData.nationLabelInfos.size() == lastNationLabelCount) {
            return;
        }
        // Clear before reading so a concurrent update during the rebuild flags it again.
        mapData.dirty = false;
        lastConfig = sc;
        lastBorderCount = mapData.nodeBorderLines.size();
        lastNodeLabelCount = mapData.nodeLabelInfos.size();
        lastTownLabelCount = mapData.townLabelInfos.size();
        lastNationLabelCount = mapData.nationLabelInfos.size();

        nationFills = sc.everything && sc.fills ? mergeChunks(mapData.buildAlphaCache(sc.fillAlpha)) : List.of();

        List<Seg> borders = new ArrayList<>();
        if (sc.everything && sc.borders) {
            int color = withAlpha(sc.white ? 0xFFFFFF : DEFAULT_NODE_COLOR, FULL_ALPHA);
            for (AechronisMapData.NodeBorderLine l : mapData.nodeBorderLines) {
                borders.add(new Seg(l.x1, l.z1, l.x2, l.z2, color, NODE_BORDER_WIDTH));
            }
        }
        nodeBorders = Buckets.of(borders);

        List<Label> out = new ArrayList<>();
        if (sc.everything) {
            // Text and icons have separate toggles; a marker with neither is dropped.
            for (AechronisMapData.NodeLabelInfo i : mapData.nodeLabelInfos.values()) {
                // The resource filter singles out one node type (towns/nations unaffected).
                if (!sc.filter.isEmpty() && !i.resources.contains(sc.filter)) continue;
                // Per-resource color (diamonds/gold/iron get their own; others white),
                // carried on the label itself. Full alpha so text stays legible.
                // With a resource filter set, keep its nodes visible at every zoom: that's the
                // point of filtering (find every node of one type at a glance).
                addLabel(out, sc.nodeLabels ? i.label : null, i.x, i.z, withAlpha(i.color, FULL_ALPHA),
                        NODE_LABEL_SCALE, icons(sc.icons, i.resources),
                        sc.filter.isEmpty() ? NODE_LABEL_MAX_HALF_VIEW : Double.MAX_VALUE);
            }
            for (AechronisMapData.NodeLabelInfo i : mapData.townLabelInfos.values()) {
                addLabel(out, sc.townLabels ? i.label : null, i.x, i.z, withAlpha(0xFFFFFF, FULL_ALPHA),
                        NODE_LABEL_SCALE, icon(sc.icons, "town"), TOWN_LABEL_MAX_HALF_VIEW);
            }
            LongOpenHashSet seen = new LongOpenHashSet(); // one nation label per chunk, as before
            for (AechronisMapData.NationLabelInfo i : mapData.nationLabelInfos) {
                if (seen.add(ChunkPos.asLong(i.x >> 4, i.z >> 4))) {
                    addLabel(out, sc.nationLabels ? i.label : null, i.x, i.z, withAlpha(i.color, FULL_ALPHA),
                            NATION_LABEL_SCALE, icon(sc.icons, "nation"), Double.MAX_VALUE);
                }
            }
        }
        labels = List.copyOf(out);
    }

    private static void addLabel(List<Label> out, String text, int x, int z, int argb, float scale,
                                 List<AechronisIcons.Icon> icons, double maxHalfView) {
        if (text != null || !icons.isEmpty()) out.add(new Label(text, x, z, argb, scale, icons, maxHalfView));
    }

    /** The war/occupation layers: small and chat-driven, so simply rebuilt every tick. */
    private static Scene buildScene(AechronisConfig cfg) {
        if (!cfg.showEverything) return Scene.EMPTY;

        // Two-phase capture/annex model: a single diagonal per captured (occupied-not-
        // annexed) node, drawn in the occupier's color. The node's base fill still shows
        // the losing nation's color underneath — the diagonal is the marker that a
        // takeover is in-progress but not yet finalized. Removed on annex.
        List<Seg> diagonals = new ArrayList<>();
        for (String tid : cfg.showOccupiedDiagonals ? mapData.capturedTerritoryIds : java.util.Set.<String>of()) {
            List<AechronisMapData.NodeBorderLine> segments = mapData.territoryDiagonals.get(tid);
            Integer color = mapData.territoryDiagonalColors.get(tid);
            if (segments == null || color == null) continue;
            for (AechronisMapData.NodeBorderLine d : segments) {
                diagonals.add(new Seg(d.x1, d.z1, d.x2, d.z2, withAlpha(color, FULL_ALPHA), cfg.occupiedDiagonalWidth));
            }
        }

        return new Scene(nationFills, nodeBorders, diagonals, labels,
                cfg.showChunkGrid ? cfg.getChunkGridArgb() : 0, cfg.chunkGridWidth, cfg.iconSize,
                cfg.autoHideBorders ? cfg.hideBordersBelowPxPerBlock : 0);
    }

    /**
     * Merges chunk -> ARGB into rectangles: same-color runs along X, then identical runs on
     * consecutive rows stacked along Z. Cuts vertex count by an order of magnitude on nation
     * territory and keeps translucent fills free of seams.
     */
    static List<Rect> mergeChunks(Long2LongMap chunks) {
        // Sort by row (z), then x. x is stored sign-flipped so negative x sorts before positive.
        long[] sorted = chunks.keySet().toLongArray();
        for (int i = 0; i < sorted.length; i++) {
            long k = sorted[i];
            sorted[i] = ((long) ChunkPos.getZ(k) << 32) | ((ChunkPos.getX(k) ^ Integer.MIN_VALUE) & 0xFFFFFFFFL);
        }
        java.util.Arrays.sort(sorted);

        List<Rect> out = new ArrayList<>();
        // Open rects from the previous row, keyed by (x1, x2, color); value = [z1].
        Map<List<Integer>, int[]> open = new HashMap<>();
        int i = 0;
        while (i < sorted.length) {
            int z = (int) (sorted[i] >> 32);
            Map<List<Integer>, int[]> next = new HashMap<>();
            while (i < sorted.length && (int) (sorted[i] >> 32) == z) {
                int x1 = rowX(sorted[i]);
                int color = (int) chunks.get(ChunkPos.asLong(x1, z));
                int x2 = x1;
                i++;
                while (i < sorted.length && (int) (sorted[i] >> 32) == z && rowX(sorted[i]) == x2 + 1
                        && (int) chunks.get(ChunkPos.asLong(x2 + 1, z)) == color) {
                    x2++;
                    i++;
                }
                List<Integer> key = List.of(x1, x2, color);
                int[] startZ = open.remove(key);
                next.put(key, startZ != null ? startZ : new int[]{z});
            }
            // Runs that didn't continue into this row (or a gap of rows) are finished.
            int prevZ = z - 1;
            open.forEach((k, s) -> out.add(chunkRect(k, s[0], prevZ)));
            open = next;
            // A run only continues if the very next row has it; flush if rows were skipped.
            if (i < sorted.length && (int) (sorted[i] >> 32) != z + 1) {
                open.forEach((k, s) -> out.add(chunkRect(k, s[0], z)));
                open = new HashMap<>();
            }
        }
        if (sorted.length > 0) {
            int lastZ = (int) (sorted[sorted.length - 1] >> 32);
            open.forEach((k, s) -> out.add(chunkRect(k, s[0], lastZ)));
        }
        return List.copyOf(out);
    }

    private static int rowX(long sortKey) {
        return (int) sortKey ^ Integer.MIN_VALUE;
    }

    private static Rect chunkRect(List<Integer> run, int z1, int z2) {
        return new Rect(run.get(0) << 4, z1 << 4, (run.get(1) + 1) << 4, (z2 + 1) << 4, run.get(2));
    }

    // ---- Drawing: geometry into the map framebuffer ----

    /**
     * @param pose          map pose whose units are blocks relative to (originX, originZ)
     * @param pxPerBlock    framebuffer pixels per block (world map fboScale, minimap zoom)
     * @param minX..maxZ    visible block area, for culling
     */
    public static void drawGeometry(ResourceKey<Level> mapDimension, Matrix4f pose, VertexConsumer buf,
                                    int originX, int originZ, double pxPerBlock,
                                    double minX, double minZ, double maxX, double maxZ) {
        Scene all = active ? scene : Scene.EMPTY;
        // Margin covers line caps sticking out of their segment's bounds.
        double m = 32 / pxPerBlock + 8;
        double x0 = minX - m, z0 = minZ - m, x1 = maxX + m, z1 = maxZ + m;
        Scene s = isCrusalisDimension(mapDimension) ? all : Scene.EMPTY;

        fills(s.nationFills, pose, buf, originX, originZ, x0, z0, x1, z1);
        // The grid isn't Crusalis data: it shows in every dimension. Drawn over the nation
        // fills (so it stays visible through them) but under borders and war markers.
        grid(all, pose, buf, originX, originZ, pxPerBlock, minX, minZ, maxX, maxZ);
        // Zoomed far out, borders merge into a mesh of lines: hide them below the threshold.
        if (pxPerBlock >= s.hideBordersBelow) {
            // Widen by the longest segment so ones starting outside the view but reaching in count.
            int sh = Buckets.SHIFT, reach = s.nodeBorders.maxLength();
            for (int rx = Mth.floor(x0 - reach) >> sh; rx <= (Mth.floor(x1 + reach) >> sh); rx++) {
                for (int rz = Mth.floor(z0 - reach) >> sh; rz <= (Mth.floor(z1 + reach) >> sh); rz++) {
                    List<Seg> bucket = s.nodeBorders.byRegion().get(ChunkPos.asLong(rx, rz));
                    if (bucket != null) lines(bucket, pose, buf, originX, originZ, pxPerBlock, x0, z0, x1, z1);
                }
            }
        }
        lines(s.occupiedDiagonals, pose, buf, originX, originZ, pxPerBlock, x0, z0, x1, z1);
    }

    private static void grid(Scene s, Matrix4f pose, VertexConsumer buf, int ox, int oz, double pxPerBlock,
                             double minX, double minZ, double maxX, double maxZ) {
        // Skip when chunks are under 8 pixels: a zoomed-out map would just turn solid.
        if (s.gridArgb == 0 || 16 * pxPerBlock < 8) return;
        float half = (float) (s.gridWidthPx / 2.0 / pxPerBlock);
        int cx0 = Mth.floor(minX / 16), cx1 = Mth.floor(maxX / 16) + 1;
        int cz0 = Mth.floor(minZ / 16), cz1 = Mth.floor(maxZ / 16) + 1;
        float zTop = (cz0 << 4) - oz, zBottom = (cz1 << 4) - oz;
        float xLeft = (cx0 << 4) - ox, xRight = (cx1 << 4) - ox;
        for (int cx = cx0; cx <= cx1; cx++) {
            float x = (cx << 4) - ox;
            quad(pose, buf, x - half, zTop, x + half, zTop, x + half, zBottom, x - half, zBottom, s.gridArgb);
        }
        for (int cz = cz0; cz <= cz1; cz++) {
            float z = (cz << 4) - oz;
            quad(pose, buf, xLeft, z - half, xRight, z - half, xRight, z + half, xLeft, z + half, s.gridArgb);
        }
    }

    private static void fills(List<Rect> rects, Matrix4f pose, VertexConsumer buf, int ox, int oz,
                              double x0, double z0, double x1, double z1) {
        for (Rect r : rects) {
            if (r.x2 < x0 || r.x1 > x1 || r.z2 < z0 || r.z1 > z1) continue;
            quad(pose, buf, r.x1 - ox, r.z1 - oz, r.x2 - ox, r.z1 - oz, r.x2 - ox, r.z2 - oz, r.x1 - ox, r.z2 - oz, r.argb);
        }
    }

    private static void lines(List<Seg> segs, Matrix4f pose, VertexConsumer buf, int ox, int oz, double pxPerBlock,
                              double x0, double z0, double x1, double z1) {
        for (Seg l : segs) {
            if (Math.max(l.x1, l.x2) < x0 || Math.min(l.x1, l.x2) > x1
                    || Math.max(l.z1, l.z2) < z0 || Math.min(l.z1, l.z2) > z1) continue;
            float dx = l.x2 - l.x1, dz = l.z2 - l.z1;
            float len = Mth.sqrt(dx * dx + dz * dz);
            if (len == 0) continue;
            // XaeroPlus-compatible width: 16 * width blocks, but at least 1.6 framebuffer pixels.
            float half = (float) (8 * Math.max(l.width * pxPerBlock, 0.1) / pxPerBlock);
            float ux = dx / len * half, uz = dz / len * half; // along the line, half-width long
            float px = -uz, pz = ux;                           // perpendicular
            // Square caps (extend by half the width) so border corners join without notches.
            float ax = l.x1 - ox - ux, az = l.z1 - oz - uz;
            float bx = l.x2 - ox + ux, bz = l.z2 - oz + uz;
            quad(pose, buf, ax + px, az + pz, bx + px, bz + pz, bx - px, bz - pz, ax - px, az - pz, l.argb);
        }
    }

    private static void quad(Matrix4f pose, VertexConsumer buf, float ax, float az, float bx, float bz,
                             float cx, float cz, float dx, float dz, int argb) {
        buf.addVertex(pose, ax, az, 0).setColor(argb);
        buf.addVertex(pose, bx, bz, 0).setColor(argb);
        buf.addVertex(pose, cx, cz, 0).setColor(argb);
        buf.addVertex(pose, dx, dz, 0).setColor(argb);
    }

    // ---- Drawing: labels, after the map is composited ----

    /**
     * World map. pose has units of blocks with the camera at the origin.
     * Text size matches the XaeroPlus version: one font pixel per framebuffer pixel at scale 0.5.
     */
    public static void drawWorldMapLabels(ResourceKey<Level> mapDimension, Matrix4f pose, MultiBufferSource buf,
                                          double cameraX, double cameraZ, double fboScale,
                                          double minX, double minZ, double maxX, double maxZ) {
        Scene s = sceneFor(mapDimension);
        if (s.labels.isEmpty()) return;
        Font font = Minecraft.getInstance().font;
        float blocksPerFontPixel = (float) (2 * Mth.clamp(1 / fboScale, 0.1, 1000));
        var window = Minecraft.getInstance().getWindow();
        // pose is in blocks; iconSize is in GUI pixels.
        float iconBlocks = (float) (s.iconSize * window.getGuiScale() / (window.getWidth() / (maxX - minX)));
        double halfView = Math.max(maxX - minX, maxZ - minZ) / 2;
        for (Label l : s.labels) {
            if (halfView > l.maxHalfView) continue;
            if (l.x < minX || l.x > maxX || l.z < minZ || l.z > maxZ) continue;
            label(font, buf, new Matrix4f(pose).translate((float) (l.x - cameraX), (float) (l.z - cameraZ), 0),
                    l, l.scale * blocksPerFontPixel, iconBlocks);
        }
    }

    /**
     * Minimap, in Xaero's over-map element space: origin at the minimap centre, rotation
     * given by (ps, pc), scaledZoom units per block. Labels whose anchor is outside the
     * visible map are skipped.
     */
    public static void drawMinimapLabels(ResourceKey<Level> mapDimension, Matrix4f pose, MultiBufferSource buf,
                                         double renderX, double renderZ, double ps, double pc, double scaledZoom,
                                         int halfView, boolean circle, float minimapScale) {
        Scene s = sceneFor(mapDimension);
        if (s.labels.isEmpty()) return;
        Font font = Minecraft.getInstance().font;
        double zoom = 2 * scaledZoom / minimapScale; // framebuffer pixels per block
        // One framebuffer pixel is minimapScale/2 units here; font pixels per framebuffer
        // pixel = 2 * scale, never smaller than 0.2 * zoom (same clamp as the world map).
        float unitsPerFontPixel = (float) (minimapScale * Math.max(1, 0.1 * zoom));
        for (Label l : s.labels) {
            double offX = l.x - renderX, offZ = l.z - renderZ;
            double x = (ps * offX - pc * offZ) * scaledZoom;
            double y = (pc * offX + ps * offZ) * scaledZoom;
            if (circle ? x * x + y * y > (double) halfView * halfView
                    : Math.abs(x) > halfView || Math.abs(y) > halfView) continue;
            label(font, buf, new Matrix4f(pose).translate((float) x, (float) y, 0), l, l.scale * unitsPerFontPixel,
                    s.iconSize * minimapScale);
        }
    }

    /** m is at the label's anchor; scale is units per font pixel, iconSize in the same units. */
    private static void label(Font font, MultiBufferSource buf, Matrix4f m, Label l, float scale, float iconSize) {
        if (!l.icons.isEmpty()) {
            // A row of icons centred on the anchor (fixed on-screen size), text just below.
            float h = iconSize / 2, step = iconSize * 1.1f;
            float x = -step * (l.icons.size() - 1) / 2;
            for (AechronisIcons.Icon icon : l.icons) {
                VertexConsumer v = buf.getBuffer(RenderTypes.textSeeThrough(icon.texture()));
                v.addVertex(m, x - h, -h, 0).setColor(-1).setUv(0, 0).setLight(0xF000F0);
                v.addVertex(m, x - h, h, 0).setColor(-1).setUv(0, 1).setLight(0xF000F0);
                v.addVertex(m, x + h, h, 0).setColor(-1).setUv(1, 1).setLight(0xF000F0);
                v.addVertex(m, x + h, -h, 0).setColor(-1).setUv(1, 0).setLight(0xF000F0);
                x += step;
            }
            if (l.text == null) return;
            m.translate(0, h + 5.5f * scale, 0);
        }
        m.scale(scale, scale, 1).translate(-font.width(l.text) / 2f, -4.5f, 0);
        font.drawInBatch(l.text, 0, 0, l.argb, true, m, buf, Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
    }

    private static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }
}
