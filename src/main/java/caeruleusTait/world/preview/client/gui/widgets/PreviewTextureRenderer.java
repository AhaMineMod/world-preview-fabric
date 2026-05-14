package caeruleusTait.world.preview.client.gui.widgets;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

public final class PreviewTextureRenderer {
    private PreviewTextureRenderer() {
    }

    public static DisplayTexture createDisplayTexture(Minecraft minecraft, int width, int height) {
        NativeImage previewImage = new NativeImage(NativeImage.Format.RGBA, width, height, true);
        NativeImage panScratchImage = new NativeImage(NativeImage.Format.RGBA, width, height, true);
        DynamicTexture texture = new DynamicTexture(() -> "world_preview:preview_display", previewImage);
        Identifier textureId = Identifier.fromNamespaceAndPath("world_preview", "dynamic/preview_display");
        minecraft.getTextureManager().register(textureId, texture);
        return new DisplayTexture(previewImage, panScratchImage, texture, textureId);
    }

    public static void closeDisplayTexture(Minecraft minecraft, Identifier textureId, NativeImage panScratchImage) {
        if (textureId != null) {
            minecraft.getTextureManager().release(textureId);
        }
        if (panScratchImage != null) {
            panScratchImage.close();
        }
    }

    public record DisplayTexture(
            NativeImage previewImage,
            NativeImage panScratchImage,
            DynamicTexture texture,
            Identifier textureId
    ) {
    }
}
