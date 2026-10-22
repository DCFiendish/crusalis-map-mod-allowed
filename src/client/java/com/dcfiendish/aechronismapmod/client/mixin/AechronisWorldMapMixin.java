package com.dcfiendish.aechronismapmod.client.mixin;

import com.dcfiendish.aechronismapmod.AechronisRenderer;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.lib.client.graphics.XaeroBufferProvider;
import xaero.map.WorldMapSession;
import xaero.map.graphics.CustomRenderTypes;
import xaero.map.gui.GuiMap;

/**
 * Crusalis overlay on Xaero's World Map. See docs/xaero-hooks.md.
 *
 * Every injector is require = 0 and captures GuiMap.render locals by name: if a Xaero update
 * moves things, the overlay quietly disappears (Mixin logs a warning) instead of crashing.
 */
@Mixin(GuiMap.class)
public class AechronisWorldMapMixin {
    @Shadow private double cameraX;
    @Shadow private double cameraZ;
    @Shadow private double scale;

    /**
     * Fills and lines, into the primary map framebuffer just before Xaero flushes its tile
     * pass: above the map tiles, below waypoints and other map elements.
     */
    @Inject(
            method = "render",
            slice = @Slice(
                    from = @At(value = "FIELD", target = "Lxaero/map/gui/GuiMap;prevLoadingLeaves:Z", opcode = Opcodes.PUTFIELD),
                    to = @At(value = "INVOKE", target = "Lxaero/map/graphics/ImprovedFramebuffer;bindDefaultFramebuffer(Lnet/minecraft/client/Minecraft;)V")
            ),
            at = @At(value = "INVOKE", target = "Lxaero/lib/client/graphics/XaeroBufferProvider;endBatch()V", ordinal = 0),
            require = 0
    )
    private void aechronis$drawGeometry(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta, CallbackInfo ci,
                                        @Local(name = "matrixStack") PoseStack matrixStack,
                                        @Local(name = "renderTypeBuffers") XaeroBufferProvider renderTypeBuffers,
                                        @Local(name = "flooredCameraX") int flooredCameraX,
                                        @Local(name = "flooredCameraZ") int flooredCameraZ,
                                        @Local(name = "fboScale") double fboScale,
                                        @Local(name = "leftBorder") double leftBorder,
                                        @Local(name = "rightBorder") double rightBorder,
                                        @Local(name = "topBorder") double topBorder,
                                        @Local(name = "bottomBorder") double bottomBorder) {
        AechronisRenderer.drawGeometry(aechronis$mapDimension(), matrixStack.last().pose(),
                renderTypeBuffers.getBuffer(CustomRenderTypes.MAP_COLOR_OVERLAY),
                flooredCameraX, flooredCameraZ, fboScale, leftBorder, topBorder, rightBorder, bottomBorder);
    }

    /**
     * Labels, after the map framebuffer is composited to the screen (so they're crisp at any
     * zoom) and just before Xaero's map elements, which therefore stay on top.
     * matrixStack is in blocks here, centred on the camera.
     */
    @Inject(
            method = "render",
            at = @At(value = "INVOKE", target = "Lxaero/map/element/MapElementRenderHandler;render(Lxaero/map/gui/GuiMap;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;DDIIDDDDDFZLxaero/map/element/HoveredMapElementHolder;Lnet/minecraft/client/Minecraft;F)Lxaero/map/element/HoveredMapElementHolder;"),
            require = 0
    )
    private void aechronis$drawLabels(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta, CallbackInfo ci,
                                      @Local(name = "matrixStack") PoseStack matrixStack,
                                      @Local(name = "vanillaRenderBuffers") MultiBufferSource.BufferSource vanillaRenderBuffers,
                                      @Local(name = "fboScale") double fboScale) {
        var window = Minecraft.getInstance().getWindow();
        double halfW = window.getWidth() / 2.0 / scale, halfH = window.getHeight() / 2.0 / scale;
        AechronisRenderer.drawWorldMapLabels(aechronis$mapDimension(), matrixStack.last().pose(), vanillaRenderBuffers,
                cameraX, cameraZ, fboScale, cameraX - halfW, cameraZ - halfH, cameraX + halfW, cameraZ + halfH);
    }

    private static ResourceKey<Level> aechronis$mapDimension() {
        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null || session.getMapProcessor().getMapWorld() == null) return null;
        return session.getMapProcessor().getMapWorld().getCurrentDimensionId();
    }
}
