package com.dcfiendish.aechronismapmod.client.mixin;

import com.dcfiendish.aechronismapmod.AechronisRenderer;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.common.minimap.render.MinimapFBORenderer;
import xaero.lib.client.graphics.XaeroBufferProvider;
import xaero.map.graphics.CustomRenderTypes;

/**
 * Crusalis fills and lines on Xaero's Minimap, drawn into its 512x512 scaling framebuffer
 * right before the first endBatch() in renderChunksToFBO: after the map chunks (world-map
 * tiles or minimap chunks, incl. cave mode) and the chunk grid, before Xaero rotates the
 * framebuffer and applies the circle/square mask — so both apply to us for free.
 * See docs/xaero-hooks.md.
 *
 * Uses the World Map's MAP_COLOR_OVERLAY render type (position-color, translucent, no
 * culling) rather than the Minimap's own MAP_CHUNK_OVERLAY, which culls back faces and would
 * drop lines drawn "backwards".
 */
@Mixin(MinimapFBORenderer.class)
public class AechronisMinimapMixin {

    @Inject(
            method = "renderChunksToFBO",
            at = @At(value = "INVOKE", target = "Lxaero/lib/client/graphics/XaeroBufferProvider;endBatch()V", ordinal = 0),
            require = 0
    )
    private void aechronis$drawGeometry(CallbackInfo ci,
                                        @Local(argsOnly = true) PoseStack matrixStack,
                                        @Local(argsOnly = true) ResourceKey<Level> mapDimension,
                                        @Local(name = "renderTypeBuffers") XaeroBufferProvider renderTypeBuffers,
                                        @Local(name = "xFloored") int xFloored,
                                        @Local(name = "zFloored") int zFloored,
                                        @Local(name = "halfMaxVisibleLength") double halfMaxVisibleLength,
                                        @Local(name = "radiusBlocks") double radiusBlocks) {
        // Pose units are blocks relative to (xFloored, zFloored); Xaero scales by zoom in the
        // model-view stack, so zoom (framebuffer pixels per block) = visible pixels / blocks.
        double zoom = halfMaxVisibleLength / radiusBlocks;
        AechronisRenderer.drawGeometry(mapDimension, matrixStack.last().pose(),
                renderTypeBuffers.getBuffer(CustomRenderTypes.MAP_COLOR_OVERLAY), xFloored, zFloored, zoom,
                xFloored - radiusBlocks, zFloored - radiusBlocks, xFloored + radiusBlocks, zFloored + radiusBlocks);
    }
}
