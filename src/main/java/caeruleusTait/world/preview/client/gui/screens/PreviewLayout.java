package caeruleusTait.world.preview.client.gui.screens;

import net.minecraft.client.gui.navigation.ScreenRectangle;

public final class PreviewLayout {
    private PreviewLayout() {
    }

    public static Metrics calculate(ScreenRectangle screenRectangle) {
        int leftWidth = Math.clamp(screenRectangle.width() / 3, 130, 180);
        int left = screenRectangle.left() + 3;
        int previewLeft = left + leftWidth + 3;
        int top = screenRectangle.top() + 2;
        int bottom = screenRectangle.bottom() - 32;
        return new Metrics(leftWidth, left, previewLeft, top, bottom);
    }

    public record Metrics(int leftWidth, int left, int previewLeft, int top, int bottom) {
    }
}
