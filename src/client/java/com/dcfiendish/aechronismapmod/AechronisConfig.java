package com.dcfiendish.aechronismapmod;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.annotation.ConfigEntry.BoundedDiscrete;
import me.shedaniel.autoconfig.annotation.ConfigEntry.Category;
import me.shedaniel.autoconfig.annotation.ConfigEntry.ColorPicker;

/** Opened from the "Open Crusalis Map Settings" keybind (default O) or Mod Menu. */
@Config(name = "aechronismapmod")
public class AechronisConfig implements ConfigData {

    // ── Overlay ──────────────────────────────────────────────
    @Category("overlay") public boolean showEverything = true;
    @Category("overlay") public boolean showNationFills = true;
    // Nation fill is the only Crusalis element with adjustable opacity — it's the one large
    // area fill capable of obscuring the underlying map, so it needs to be tunable.
    // Everything else (node borders, occupied diagonal) is a
    // thin line/marker overlay, always rendered fully opaque.
    @Category("overlay") @BoundedDiscrete(min = 0, max = 100) public int nationFillOpacity = 39;
    @Category("overlay") public boolean showNodeBorders = true;
    @Category("overlay") public boolean whiteBorders = false;
    /** Hide node borders when the map shows fewer than this many pixels per block. */
    @Category("overlay") public boolean autoHideBorders = true;
    // Renamed from hideBordersBelowZoom (default 0.5) so existing configs pick up the new,
    // further-out default instead of keeping the old cutoff.
    @Category("overlay") public float hideBordersBelowPxPerBlock = 0.2f;

    // ── War ──────────────────────────────────────────────────
    // Territory-level only: the occupied (captured-not-annexed) diagonal. No per-chunk
    // war markers in this build (server admin ruling: node-by-node changes only).
    @Category("war") public boolean showOccupiedDiagonals = true;
    @Category("war") public float occupiedDiagonalWidth = 0.14f;

    // ── Labels ───────────────────────────────────────────────
    @Category("labels") public boolean showNodeLabels = true;
    @Category("labels") public boolean showTownLabels = true;
    @Category("labels") public boolean showNationLabels = true;

    // ── Chunk grid (any dimension, while the mod is active) ──
    @Category("grid") public boolean showChunkGrid = false;
    @Category("grid") @ColorPicker public int chunkGridColor = 0xFFFFFF;
    @Category("grid") @BoundedDiscrete(min = 0, max = 100) public int chunkGridOpacity = 25;
    /** In framebuffer pixels. */
    @Category("grid") @BoundedDiscrete(min = 1, max = 5) public int chunkGridWidth = 1;

    // ── F3+G chunk borders, colored by your relation to the chunk's holder ──
    /** The mod's own in-world chunk borders; toggled by the "Toggle Chunk Borders" key. */
    @Category("borders") public boolean showOwnChunkBorders = false;
    /** Chunks around yours to draw, 0 = only yours. */
    @Category("borders") @BoundedDiscrete(min = 0, max = 4) public int ownChunkBorderRadius = 1;
    /** Off Crusalis, outside the Overworld, or for chunks no node covers. */
    @Category("borders") @ColorPicker public int chunkBorderDefaultColor = 0xFFFF55;
    @Category("borders") public boolean autoChunkBorders = true;
    @Category("borders") @ColorPicker public int chunkBorderTownColor = 0x55FF55;
    @Category("borders") @ColorPicker public int chunkBorderNationColor = 0x00AA00;
    @Category("borders") @ColorPicker public int chunkBorderAllyColor = 0x00AAAA;
    @Category("borders") @ColorPicker public int chunkBorderEnemyColor = 0xFF5555;
    @Category("borders") @ColorPicker public int chunkBorderNeutralColor = 0xFFAA00;

    // ── Icons (PNGs from config/aechronismapmod/icons/) ──────
    @Category("icons") public boolean showIcons = true;
    /** Icon edge length in GUI pixels; fixed on screen at every zoom. */
    @Category("icons") @BoundedDiscrete(min = 4, max = 32) public int iconSize = 8;
    /** Node type to single out (e.g. "wheat"), or empty for all. Cycled with its keybind. */
    @Category("icons") public String resourceFilter = "";

    // ── Getters used by renderer ──────────────────────────────
    public int getNationFillAlpha() { return (int)(nationFillOpacity / 100f * 255); }
    public int getChunkGridArgb() { return ((int)(chunkGridOpacity / 100f * 255) << 24) | (chunkGridColor & 0xFFFFFF); }

    @Override
    public void validatePostLoad() {
        // A hand-edited or older config file can leave these null / out of range.
        if (resourceFilter == null) resourceFilter = "";
        if (hideBordersBelowPxPerBlock < 0) hideBordersBelowPxPerBlock = 0;
    }

    public static AechronisConfig get() {
        return AutoConfig.getConfigHolder(AechronisConfig.class).getConfig();
    }
}
