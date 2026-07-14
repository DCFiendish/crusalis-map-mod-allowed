package com.dcfiendish.aechronismapmod;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.AutoConfig;

@Config(name = "aechronismapmod")
public class AechronisConfig implements ConfigData {

    // ── Map Overlay ──────────────────────────────────────────
    public boolean showEverything = true;
    // Nation fill is the only element with adjustable opacity — it's the one large
    // area fill capable of obscuring the underlying map, so it needs to be tunable.
    // Everything else below (node borders, occupied diagonal) is a thin line/marker
    // overlay, always rendered fully opaque.
    public int nationFillOpacity = 39;
    public float occupiedDiagonalWidth = 0.14f;
    public boolean showNationFills = true;
    public boolean showNodeBorders = true;
    public boolean showNodeLabels = true;
    public boolean showTownLabels = true;
    public boolean showNationLabels = true;
    public boolean showPorts = true;
    public boolean whiteBorders = false;

    // ── Getters used by renderer ──────────────────────────────
    public int getNationFillAlpha() { return (int)(nationFillOpacity / 100f * 255); }

    public static AechronisConfig get() {
        return AutoConfig.getConfigHolder(AechronisConfig.class).getConfig();
    }
}