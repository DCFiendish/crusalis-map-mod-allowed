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
    // Everything else (node borders, occupied diagonal) is a thin line/marker overlay,
    // always rendered fully opaque.
    @Category("overlay") @BoundedDiscrete(min = 0, max = 100) public int nationFillOpacity = 39;
    @Category("overlay") public boolean showNodeBorders = true;
    @Category("overlay") public boolean whiteBorders = false;
    /** Hide node borders when the map shows fewer than this many pixels per block. */
    @Category("overlay") public boolean autoHideBorders = true;
    // Renamed from hideBordersBelowZoom (default 0.5) so existing configs pick up the new,
    // further-out default instead of keeping the old cutoff.
    @Category("overlay") public float hideBordersBelowPxPerBlock = 0.2f;

    // ── War ──────────────────────────────────────────────────
    // Occupied diagonal is territory-level (whole nodes). Per-chunk war markers are
    // not part of this build.
    @Category("war") public boolean showOccupiedDiagonals = true;
    @Category("war") public float occupiedDiagonalWidth = 0.14f;
    /**
     * Color occupied diagonals by your relation to the capturing nation instead of that
     * nation's own color.
     */
    @Category("war") public boolean relationCaptureColors = false;
    /** Comma-separated nation names counted as allies, on top of any the data feed lists. */
    @Category("war") public String allyNations = "";
    @Category("war") @ColorPicker public int captureOwnColor = 0x55FF55;
    @Category("war") @ColorPicker public int captureAllyColor = 0x5555FF;
    @Category("war") @ColorPicker public int captureEnemyColor = 0xFF5555;
    /**
     * Mid-week warzones: outline each zone, name it, and add its holder to each territory's
     * label. Holders' colors fill the land either way, like any owner's.
     */
    @Category("war") public boolean showWarzones = true;
    @Category("war") @ColorPicker public int warzoneColor = 0xFF3030;
    @Category("war") public float warzoneBorderWidth = 0.2f;

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

    // ── Icons (PNGs from config/aechronismapmod/icons/) ──────
    /** Resource node icons; independent of the resource label toggle (Labels tab). */
    @Category("icons") public boolean showResourceIcons = true;
    /** Town and nation capital icons (town.png / nation.png). */
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
        if (allyNations == null) allyNations = "";
        if (hideBordersBelowPxPerBlock < 0) hideBordersBelowPxPerBlock = 0;
    }

    public static AechronisConfig get() {
        return AutoConfig.getConfigHolder(AechronisConfig.class).getConfig();
    }
}
