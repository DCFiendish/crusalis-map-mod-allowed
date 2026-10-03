package com.dcfiendish.aechronismapmod.client.mixin;

import com.dcfiendish.aechronismapmod.AechronisRenderer;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.common.minimap.render.MinimapRenderer;
import xaero.lib.client.graphics.XaeroBufferProvider;

/**
 * Crusalis labels on Xaero's Minimap, drawn upright in the over-map element pass (after the
 * rotated map is composited, before radar icons and the compass-over-everything option), using
 * the same block-to-screen transform Xaero uses for its own over-map elements.
 * See docs/xaero-hooks.md.
 */
@Mixin(MinimapRenderer.class)
public class AechronisMinimapLabelMixin {
    @Shadow @Final protected PoseStack matrixStack;

    @Inject(
            method = "renderMinimap",
            at = @At(value = "INVOKE", target = "Lxaero/hud/minimap/element/render/over/MinimapElementOverMapRendererHandler;prepareRender(DDDIIIIZF)V"),
            require = 0
    )
    private void aechronis$drawLabels(CallbackInfo ci,
                                      @Local(name = "minimapScale") float minimapScale,
                                      @Local(name = "ps") double ps,
                                      @Local(name = "pc") double pc,
                                      @Local(name = "scaledZoom") double scaledZoom,
                                      @Local(name = "halfFrame") int halfFrame,
                                      @Local(name = "circleShape") boolean circleShape,
                                      @Local(name = "renderPos") Vec3 renderPos,
                                      @Local(name = "mapDimension") ResourceKey<Level> mapDimension,
                                      @Local(name = "renderTypeBuffers") XaeroBufferProvider renderTypeBuffers) {
        AechronisRenderer.drawMinimapLabels(mapDimension, matrixStack.last().pose(), renderTypeBuffers,
                renderPos.x, renderPos.z, ps, pc, scaledZoom, halfFrame, circleShape, minimapScale);
    }
}
