package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.client.WorldPreviewClient;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.Identifier;

import java.awt.*;

public class ColorChooser extends AbstractWidget {

    public static final int INITIAL_SV_SQUARE_SIZE = 128;
    public static final int INITIAL_H_BAR_WIDTH = 16;
    public static final int SEPARATOR = 10;
    public static final int INITIAL_FINAL_COLOR_HEIGHT = 20;
    private static int nextTextureId = 0;

    private int svSquareSize;
    private int hBarWidth;
    private int finalColorHeight;

    private float hue = 0f;
    private float saturation = 0f;
    private float value = 0f;

    private int argbColor = 0xFF000000;
    private int argbHueOnly = 0xFF000000;
    private int cachedHueKey = Integer.MIN_VALUE;

    private ColorUpdater updater;
    private final Identifier svTextureId;
    private final Identifier hTextureId;
    private NativeImage svTextureImage;
    private NativeImage hTextureImage;
    private DynamicTexture svTexture;
    private DynamicTexture hTexture;

    public ColorChooser(int x, int y) {
        super(x, y, 10, 10, CommonComponents.EMPTY);
        final int textureId = nextTextureId++;
        svTextureId = Identifier.tryBuild("world_preview", "dynamic/color_chooser/sv_" + textureId);
        hTextureId = Identifier.tryBuild("world_preview", "dynamic/color_chooser/h_" + textureId);
        svSquareSize = INITIAL_SV_SQUARE_SIZE;
        hBarWidth = INITIAL_H_BAR_WIDTH;
        finalColorHeight = INITIAL_FINAL_COLOR_HEIGHT;
        recalculateSize();
        recreateTextures();
    }

    private void recalculateSize() {
        width = svSquareSize + SEPARATOR + hBarWidth;
        height = svSquareSize + SEPARATOR + finalColorHeight;
    }

    public void setSquareSize(int squareSize) {
        int safeSquareSize = Math.max(1, squareSize);
        if (svSquareSize == safeSquareSize) {
            return;
        }
        float scalor = (float) safeSquareSize / (float) INITIAL_SV_SQUARE_SIZE;
        svSquareSize = safeSquareSize;
        hBarWidth = Math.max(1, (int) (INITIAL_H_BAR_WIDTH * scalor));
        finalColorHeight = Math.max(1, (int) (INITIAL_FINAL_COLOR_HEIGHT * scalor));
        recalculateSize();
        recreateTextures();
    }

    private void releaseTextures() {
        Minecraft.getInstance().getTextureManager().release(svTextureId);
        Minecraft.getInstance().getTextureManager().release(hTextureId);
        svTextureImage = null;
        hTextureImage = null;
        svTexture = null;
        hTexture = null;
    }

    private void recreateTextures() {
        releaseTextures();
        svTextureImage = new NativeImage(svSquareSize, svSquareSize, false);
        hTextureImage = new NativeImage(hBarWidth, svSquareSize, false);
        svTexture = new DynamicTexture(() -> "world_preview:color_chooser_sv", svTextureImage);
        hTexture = new DynamicTexture(() -> "world_preview:color_chooser_h", hTextureImage);
        Minecraft.getInstance().getTextureManager().register(svTextureId, svTexture);
        Minecraft.getInstance().getTextureManager().register(hTextureId, hTexture);

        redrawHueTexture();
        cachedHueKey = Integer.MIN_VALUE;
        redrawSVTexture();
    }

    private void ensureTextures() {
        if (svTexture == null || hTexture == null || svTextureImage == null || hTextureImage == null) {
            recreateTextures();
            return;
        }
        if (svTextureImage.getWidth() != svSquareSize || svTextureImage.getHeight() != svSquareSize || hTextureImage.getWidth() != hBarWidth || hTextureImage.getHeight() != svSquareSize) {
            recreateTextures();
            return;
        }
        int hueKey = Math.round(hue * 360f);
        if (hueKey != cachedHueKey) {
            redrawSVTexture();
        }
    }

    private void redrawHueTexture() {
        for (int y = 0; y < svSquareSize; ++y) {
            final float localHue = svSquareSize <= 1 ? 0.0F : 1.0F - ((float) y / (float) (svSquareSize - 1));
            final int rgb = (Color.HSBtoRGB(localHue, 1.0F, 1.0F) & 0x00FFFFFF) | 0xFF000000;
            for (int x = 0; x < hBarWidth; ++x) {
                hTextureImage.setPixel(x, y, rgb);
            }
        }
        hTexture.upload();
    }

    private void redrawSVTexture() {
        for (int x = 0; x < svSquareSize; ++x) {
            final float sat = svSquareSize <= 1 ? 0.0F : (float) x / (float) (svSquareSize - 1);
            for (int y = 0; y < svSquareSize; ++y) {
                final float val = svSquareSize <= 1 ? 0.0F : 1.0F - ((float) y / (float) (svSquareSize - 1));
                final int rgb = (Color.HSBtoRGB(hue, sat, val) & 0x00FFFFFF) | 0xFF000000;
                svTextureImage.setPixel(x, y, rgb);
            }
        }
        cachedHueKey = Math.round(hue * 360f);
        svTexture.upload();
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int i, int j, float f) {
        ensureTextures();

        // render background
        guiGraphics.fill(getX() - 2, getY() - 2, getX() + width + 2, getY() + height + 2, 0x77000000);

        // Render saturation value chooser
        int leftX = getX();
        int topY = getY();
        int rightX = leftX + svSquareSize;
        int botY = topY + svSquareSize;
        WorldPreviewClient.renderTexture(guiGraphics, svTextureId, leftX, topY, rightX, botY);

        // Render saturation value indicator
        int satX = leftX + Math.round(saturation * (svSquareSize - 1));
        int valY = topY + Math.round((1f - value) * (svSquareSize - 1));
        guiGraphics.fill(satX - 4, valY - 4, satX + 4, valY + 4, value > .3 ? 0xFF000000 : 0xFFFFFFFF);
        guiGraphics.fill(satX - 3, valY - 3, satX + 3, valY + 3, argbColor);

        // Render Hue chooser
        leftX = rightX + SEPARATOR;
        rightX = leftX + hBarWidth;
        WorldPreviewClient.renderTexture(guiGraphics, hTextureId, leftX, topY, rightX, botY);

        // Render saturation value indicator
        int hueY = topY + Math.round((1f - hue) * (svSquareSize - 1));
        guiGraphics.fill(leftX - 2, hueY - 4, rightX + 2, hueY + 4, 0xFF000000);
        guiGraphics.fill(leftX - 1, hueY - 3, rightX + 1, hueY + 3, argbHueOnly);

        // Render final color box
        guiGraphics.fill(getX(), botY + SEPARATOR, getX() + width, getY() + height, argbColor);
    }

    public boolean mouseEvent(double mouseX, double mouseY, MouseButtonInfo buttonInfo, boolean playSound) {
        if (!this.active || !this.visible || !isValidClickButton(buttonInfo) || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        if (Minecraft.getInstance().screen != null) {
            Minecraft.getInstance().screen.setFocused(this);
        }

        double leftX = getX();
        double topY = getY();
        double rightX = leftX + svSquareSize;
        double botY = topY + svSquareSize;

        boolean updated = false;
        boolean hueUpdated = false;

        // check if mouse in SV selector
        if (mouseX >= leftX && mouseX <= rightX && mouseY >= topY && mouseY <= botY) {
            if (playSound) {
                this.playDownSound(Minecraft.getInstance().getSoundManager());
            }
            value = 1f - (float) ((mouseY - topY) / (botY - topY));
            saturation = (float) ((mouseX - leftX) / (rightX - leftX));
            updated = true;
        }

        leftX = rightX + SEPARATOR;
        rightX = leftX + hBarWidth;

        // check if mouse in hue selector
        if (mouseX >= leftX && mouseX <= rightX && mouseY >= topY && mouseY <= botY) {
            if (playSound) {
                this.playDownSound(Minecraft.getInstance().getSoundManager());
            }
            hue = 1f - (float) ((mouseY - topY) / (botY - topY));
            updated = true;
            hueUpdated = true;
        }

        argbColor = Color.HSBtoRGB(hue, saturation, value);
        argbHueOnly = Color.HSBtoRGB(hue, 1f, 1f);
        if (hueUpdated) {
            cachedHueKey = Integer.MIN_VALUE;
        }
        if (updated) {
            runUpdater();
        }
        return updated;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        return mouseEvent(event.x(), event.y(), event.buttonInfo(), true);
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double mouseX, double mouseY) {
        mouseEvent(mouseX, mouseY, event.buttonInfo(), false);
    }

    public void runUpdater() {
        if (updater == null) {
            return;
        }

        updater.doUpdate(
                (int) (hue * 360f),
                (int) (saturation * 100f),
                (int) (value * 100f)
        );
    }

    public void setUpdater(ColorUpdater updater) {
        this.updater = updater;
    }

    public void updateHSV(int h, int s, int v) {
        hue = (float) h / 360f;
        saturation = (float) s / 100f;
        value = (float) v / 100f;
        argbColor = Color.HSBtoRGB(hue, saturation, value);
        argbHueOnly = Color.HSBtoRGB(hue, 1f, 1f);
        cachedHueKey = Integer.MIN_VALUE;
        runUpdater();
    }

    public void updateRGB(int rgb) {
        final int r = (rgb >> 16) & 0xFF;
        final int g = (rgb >> 8) & 0xFF;
        final int b = (rgb >> 0) & 0xFF;

        final float[] hsv = Color.RGBtoHSB(r, g, b, null);
        hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
        argbColor = Color.HSBtoRGB(hue, saturation, value);
        argbHueOnly = Color.HSBtoRGB(hue, 1f, 1f);
        cachedHueKey = Integer.MIN_VALUE;
        runUpdater();
    }

    public int colorRGB() {
        return argbColor & 0x00FFFFFF;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // Do nothing
    }

    public interface ColorUpdater {
        void doUpdate(int h, int s, int v);
    }
}
