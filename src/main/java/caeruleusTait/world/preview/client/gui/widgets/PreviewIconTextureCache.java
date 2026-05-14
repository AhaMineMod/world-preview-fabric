package caeruleusTait.world.preview.client.gui.widgets;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

public class PreviewIconTextureCache implements AutoCloseable {
    private final Minecraft minecraft;
    private IconData[] structureIcons = new IconData[0];
    private IconData playerIcon;
    private IconData spawnIcon;

    public PreviewIconTextureCache(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    public void reload(NativeImage[] rawStructureIcons, NativeImage rawPlayerIcon, NativeImage rawSpawnIcon) {
        close();
        structureIcons = new IconData[rawStructureIcons.length];
        for (int i = 0; i < rawStructureIcons.length; ++i) {
            structureIcons[i] = registerIcon(
                    "world_preview:structure_icon_" + i,
                    Identifier.tryBuild("world_preview", "dynamic/preview_display/structure_" + i),
                    rawStructureIcons[i]
            );
        }
        playerIcon = registerIcon(
                "world_preview:player_icon",
                Identifier.tryBuild("world_preview", "dynamic/preview_display/player"),
                rawPlayerIcon
        );
        spawnIcon = registerIcon(
                "world_preview:spawn_icon",
                Identifier.tryBuild("world_preview", "dynamic/preview_display/spawn"),
                rawSpawnIcon
        );
    }

    private IconData registerIcon(String textureLabel, Identifier textureId, NativeImage icon) {
        DynamicTexture texture = new DynamicTexture(() -> textureLabel, icon);
        minecraft.getTextureManager().register(textureId, texture);
        return new IconData(icon.getWidth(), icon.getHeight(), texture, textureId);
    }

    public IconData[] structureIcons() {
        return structureIcons;
    }

    public IconData playerIcon() {
        return playerIcon;
    }

    public IconData spawnIcon() {
        return spawnIcon;
    }

    @Override
    public void close() {
        Arrays.stream(structureIcons).forEach(IconData::close);
        structureIcons = new IconData[0];
        if (playerIcon != null) {
            playerIcon.close();
            playerIcon = null;
        }
        if (spawnIcon != null) {
            spawnIcon.close();
            spawnIcon = null;
        }
    }

    public record IconData(int width, int height, @NotNull DynamicTexture texture, @NotNull Identifier textureId) {
        public void close() {
            Minecraft.getInstance().getTextureManager().release(textureId);
        }
    }
}
