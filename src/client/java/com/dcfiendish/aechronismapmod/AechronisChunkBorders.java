package com.dcfiendish.aechronismapmod;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Chunk-border colors by your relation to the chunk's holder: own town, own nation,
 * ally, enemy or neutral. Ownership is node/territory-level from towns.json (an occupier
 * counts as the holder); per-chunk war state is never read. Drawn by {@link #draw} as the
 * mod's own borders; vanilla F3+G is left alone (Lunar replaces it anyway).
 */
public final class AechronisChunkBorders {
    private AechronisChunkBorders() {}

    /** Relation color (opaque ARGB) for the chunk, or {@code plain} when not coloring by relation. */
    public static int color(int chunkX, int chunkZ, int plain) {
        Minecraft mc = Minecraft.getInstance();
        AechronisConfig cfg = AechronisConfig.get();
        AechronisMapData data = AechronisMapMod.mapData;
        if (!cfg.autoChunkBorders || !cfg.showEverything || !AechronisRenderer.isActive()
                || data == null || mc.player == null || mc.level == null
                || mc.level.dimension() != Level.OVERWORLD) {
            return plain;
        }
        String tid = data.chunkToTerritoryId.get(ChunkPos.asLong(chunkX, chunkZ));
        if (tid == null) return plain; // unclaimable / unknown chunk
        String nation = data.territoryHolderNation.get(tid);
        int rgb;
        if (nation == null) {
            rgb = cfg.chunkBorderNeutralColor; // unclaimed node
        } else {
            String me = mc.player.getGameProfile().name();
            String myNation = data.playerNationMap.get(me);
            rgb = relationColor(cfg, data, myNation, data.playerTownMap.get(me),
                    nation, data.territoryHolderTown.get(tid));
        }
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    /**
     * The mod's own chunk borders, toggled by its keybind: vertical lines at each chunk's
     * corners (thick) and along its edges, plus edge rings near the player's height, all
     * sized and spaced by the Chunk Borders settings, for every chunk within the configured radius. Each chunk is drawn slightly inset so
     * two neighbours with different relations both stay visible. Emitted as vanilla gizmos
     * from Fabric's END_EXTRACTION, so it doesn't depend on ChunkBorderRenderer.
     */
    public static void draw(Minecraft mc) {
        AechronisConfig cfg = AechronisConfig.get();
        if (!cfg.showOwnChunkBorders || mc.player == null || mc.level == null) return;
        int plain = 0xFF000000 | (cfg.chunkBorderDefaultColor & 0xFFFFFF);
        double minY = mc.level.getMinY(), maxY = mc.level.getMaxY() + 1;
        int py = mc.player.getBlockY();
        int pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z;
        int r = cfg.ownChunkBorderRadius;
        final double in = 0.04;
        float w = cfg.chunkBorderLineWidth;
        try (var ignored = mc.levelRenderer.collectPerFrameGizmos()) {
            for (int cx = pcx - r; cx <= pcx + r; cx++) {
                for (int cz = pcz - r; cz <= pcz + r; cz++) {
                    int c = color(cx, cz, plain);
                    double x0 = cx * 16 + in, x1 = cx * 16 + 16 - in;
                    double z0 = cz * 16 + in, z1 = cz * 16 + 16 - in;
                    if (cfg.chunkBorderShowCorners) {
                        float cw = cfg.chunkBorderCornerWidth;
                        for (double x : new double[]{x0, x1}) {
                            for (double z : new double[]{z0, z1}) {
                                Gizmos.line(new Vec3(x, minY, z), new Vec3(x, maxY, z), c, cw);
                            }
                        }
                    }
                    int vs = cfg.chunkBorderVerticalSpacing;
                    for (int i = vs; vs > 0 && i < 16; i += vs) {
                        double x = cx * 16 + i, z = cz * 16 + i;
                        Gizmos.line(new Vec3(x, minY, z0), new Vec3(x, maxY, z0), c, w);
                        Gizmos.line(new Vec3(x, minY, z1), new Vec3(x, maxY, z1), c, w);
                        Gizmos.line(new Vec3(x0, minY, z), new Vec3(x0, maxY, z), c, w);
                        Gizmos.line(new Vec3(x1, minY, z), new Vec3(x1, maxY, z), c, w);
                    }
                    int hs = cfg.chunkBorderHorizontalSpacing;
                    // Rings only within 32 blocks of the player's height.
                    for (int y = Math.floorDiv(py - 32, Math.max(hs, 1)) * hs; hs > 0 && y <= py + 32; y += hs) {
                        if (y < minY || y > maxY) continue;
                        Gizmos.line(new Vec3(x0, y, z0), new Vec3(x1, y, z0), c, w);
                        Gizmos.line(new Vec3(x0, y, z1), new Vec3(x1, y, z1), c, w);
                        Gizmos.line(new Vec3(x0, y, z0), new Vec3(x0, y, z1), c, w);
                        Gizmos.line(new Vec3(x1, y, z0), new Vec3(x1, y, z1), c, w);
                    }
                }
            }
        }
    }

    private static int relationColor(AechronisConfig cfg, AechronisMapData data,
                                     String myNation, String myTown, String nation, String town) {
        if (myNation == null) return cfg.chunkBorderNeutralColor; // you have no town
        if (myNation.equals(nation)) {
            return myTown != null && myTown.equals(town) ? cfg.chunkBorderTownColor : cfg.chunkBorderNationColor;
        }
        // Allied needs both sides to list each other; enemy needs either side.
        if (data.nationAlliesMap.getOrDefault(myNation, Set.of()).contains(nation)
                && data.nationAlliesMap.getOrDefault(nation, Set.of()).contains(myNation)) {
            return cfg.chunkBorderAllyColor;
        }
        if (data.nationEnemiesMap.getOrDefault(myNation, Set.of()).contains(nation)
                || data.nationEnemiesMap.getOrDefault(nation, Set.of()).contains(myNation)) {
            return cfg.chunkBorderEnemyColor;
        }
        return cfg.chunkBorderNeutralColor;
    }
}
