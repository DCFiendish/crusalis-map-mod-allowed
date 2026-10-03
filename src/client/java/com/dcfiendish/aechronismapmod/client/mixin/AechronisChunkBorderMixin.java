package com.dcfiendish.aechronismapmod.client.mixin;

import com.dcfiendish.aechronismapmod.AechronisChunkBorders;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.debug.ChunkBorderRenderer;
import net.minecraft.core.SectionPos;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Recolors the F3+G chunk borders by relation to the chunk's holder (see AechronisChunkBorders).
 * 1.21.11's emitGizmos colors the grid of the camera's own chunk from three static fields,
 * and the corner pillars of the surrounding chunks from one inline ARGB.colorFromFloat call
 * inside a -16..32 step-16 offset loop. require = 0: a renamed target just leaves vanilla colors.
 */
@Mixin(ChunkBorderRenderer.class)
public class AechronisChunkBorderMixin {
    private static final String EMIT = "emitGizmos(DDDLnet/minecraft/util/debug/DebugValueAccess;Lnet/minecraft/client/renderer/culling/Frustum;F)V";

    @ModifyExpressionValue(method = EMIT, require = 0, at = {
            @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = "Lnet/minecraft/client/renderer/debug/ChunkBorderRenderer;CELL_BORDER:I"),
            @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = "Lnet/minecraft/client/renderer/debug/ChunkBorderRenderer;YELLOW:I"),
            @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = "Lnet/minecraft/client/renderer/debug/ChunkBorderRenderer;MAJOR_LINES:I")
    })
    private int aechronis$ownChunkColor(int vanilla, @Local SectionPos section) {
        return AechronisChunkBorders.color(section.x(), section.z(), vanilla);
    }

    // The ints are the loop's block offsets from the section's corner (slots 18 and 19).
    @ModifyExpressionValue(method = EMIT, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;colorFromFloat(FFFF)I"))
    private int aechronis$pillarColor(int vanilla, @Local SectionPos section,
                                      @Local(ordinal = 0) int dx, @Local(ordinal = 1) int dz) {
        return AechronisChunkBorders.color(section.x() + Math.floorDiv(dx, 16), section.z() + Math.floorDiv(dz, 16), vanilla);
    }
}
