package caeruleusTait.world.preview.client.gui.widgets;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

public final class PreviewAccelerationBackend {
    private PreviewAccelerationBackend() {
    }

    public static void probeAndLog() {
        final ProbeResult probeResult = probeGpuCandidate();
        if (!probeResult.available()) {
            LOGGER.debug("World preview GPU acceleration fallback active: {}", probeResult.reason());
            return;
        }

        LOGGER.debug("World preview GPU colorization candidate detected; CPU writer remains the compatibility fallback");
    }

    private static ProbeResult probeGpuCandidate() {
        try {
            if (!RenderSystem.isOnRenderThread()) {
                return new ProbeResult(false, "not on render thread during probe");
            }

            GL.getCapabilities();
            return new ProbeResult(true, "OpenGL capabilities available");
        } catch (IllegalStateException | LinkageError e) {
            return new ProbeResult(false, e.getClass().getSimpleName());
        }
    }

    private record ProbeResult(boolean available, String reason) {
    }
}
