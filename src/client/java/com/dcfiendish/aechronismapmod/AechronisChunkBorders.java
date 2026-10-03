package com.dcfiendish.aechronismapmod;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Set;

/**
 * F3+G chunk-border colors by your relation to the chunk's holder: own town, own nation,
 * ally, enemy or neutral. Ownership is node/territory-level from towns.json (an occupier
 * counts as the holder); per-chunk war state is never read. Used by AechronisChunkBorderMixin.
 */
public final class AechronisChunkBorders {
    private AechronisChunkBorders() {}

    /** Relation color (opaque ARGB) for the chunk, or {@code vanilla} when not recoloring. */
    public static int color(int chunkX, int chunkZ, int vanilla) {
        Minecraft mc = Minecraft.getInstance();
        AechronisConfig cfg = AechronisConfig.get();
        AechronisMapData data = AechronisMapMod.mapData;
        if (!cfg.autoChunkBorders || !cfg.showEverything || !AechronisRenderer.isActive()
                || data == null || mc.player == null || mc.level == null
                || mc.level.dimension() != Level.OVERWORLD) {
            return vanilla;
        }
        String tid = data.chunkToTerritoryId.get(ChunkPos.asLong(chunkX, chunkZ));
        if (tid == null) return vanilla; // unclaimable / unknown chunk
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
