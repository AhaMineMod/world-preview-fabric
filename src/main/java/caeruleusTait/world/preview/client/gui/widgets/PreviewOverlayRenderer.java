package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.client.WorldPreviewClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

public final class PreviewOverlayRenderer {
    private PreviewOverlayRenderer() {
    }

    public static void renderStickyIcon(
            GuiGraphics guiGraphics,
            Identifier textureId,
            int iconWidth,
            int iconHeight,
            int textureCenterX,
            int textureCenterZ,
            int textureWidth,
            int textureHeight,
            int originX,
            int originY,
            double guiScale
    ) {
        final int clampedCenterX = Math.clamp(textureCenterX, 0, textureWidth);
        final int clampedCenterZ = Math.clamp(textureCenterZ, 0, textureHeight);
        final int texStartX = clampedCenterX - iconWidth;
        final int texStartZ = clampedCenterZ - iconHeight;

        final int rXMin = originX + (int) Math.round(texStartX / guiScale);
        final int rZMin = originY + (int) Math.round(texStartZ / guiScale);
        final int rXMax = rXMin + Math.max(1, (int) Math.round((iconWidth * 2) / guiScale));
        final int rZMax = rZMin + Math.max(1, (int) Math.round((iconHeight * 2) / guiScale));

        WorldPreviewClient.renderTexture(guiGraphics, textureId, rXMin, rZMin, rXMax, rZMax);
    }
}
