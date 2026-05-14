package caeruleusTait.world.preview.client.gui.widgets;

import java.util.Locale;

public final class PreviewViewport {
    private PreviewViewport() {
    }

    public static String formatZoomLabel(double zoomFactor) {
        final double roundedZoom = Math.round(zoomFactor * 100.0) / 100.0;
        if (Math.abs(roundedZoom - Math.rint(roundedZoom)) < 0.0001) {
            return String.format(Locale.ROOT, "%.0fx", roundedZoom);
        }
        if (Math.abs((roundedZoom * 10.0) - Math.rint(roundedZoom * 10.0)) < 0.0001) {
            return String.format(Locale.ROOT, "%.1fx", roundedZoom);
        }
        return String.format(Locale.ROOT, "%.2fx", roundedZoom);
    }

    public static double effectiveScaleBlockPos(int scaleBlockPos, double zoomFactor, double silentZoomFactor) {
        return scaleBlockPos / (zoomFactor * silentZoomFactor);
    }

    public static int minBlock(int center, int textureSize, double effectiveScale) {
        return (int) Math.floor(center - (textureSize * effectiveScale / 2.0) - 1.0);
    }

    public static int maxBlock(int center, int textureSize, double effectiveScale) {
        return (int) Math.ceil(center + (textureSize * effectiveScale / 2.0) + 1.0);
    }

    public static int blockToTexture(int block, int minBlock, double effectiveScale) {
        return (int) Math.floor((block - minBlock) / effectiveScale);
    }
}
