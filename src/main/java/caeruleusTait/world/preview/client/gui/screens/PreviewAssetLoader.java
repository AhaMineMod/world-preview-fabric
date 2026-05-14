package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.backend.color.PreviewData;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

public final class PreviewAssetLoader {
    private static final Identifier UNKNOWN_STRUCTURE_ICON = Identifier.parse("world_preview:textures/structure/unknown.png");
    private static final Identifier PLAYER_ICON = Identifier.parse("world_preview:textures/etc/player.png");
    private static final Identifier SPAWN_ICON = Identifier.parse("world_preview:textures/etc/bed.png");

    private PreviewAssetLoader() {
    }

    public static Assets loadAssets(PreviewData previewData, ResourceManager builtinResourceManager, ResourceManager sampleResourceManager) {
        NativeImage[] structureIcons = loadStructureIcons(previewData, builtinResourceManager, sampleResourceManager);
        NativeImage playerIcon = loadRequiredIcon(builtinResourceManager, PLAYER_ICON);
        NativeImage spawnIcon = loadRequiredIcon(builtinResourceManager, SPAWN_ICON);
        return new Assets(structureIcons, playerIcon, spawnIcon);
    }

    @SuppressWarnings("resource")
    private static NativeImage[] loadStructureIcons(PreviewData previewData, ResourceManager builtinResourceManager, ResourceManager sampleResourceManager) {
        Map<Identifier, NativeImage> icons = new HashMap<>();
        NativeImage[] structureIcons = new NativeImage[previewData.structId2StructData().length];
        for (int i = 0; i < previewData.structId2StructData().length; ++i) {
            PreviewData.StructureData data = previewData.structId2StructData()[i];
            structureIcons[i] = icons.computeIfAbsent(data.icon(), x -> loadStructureIcon(x, builtinResourceManager, sampleResourceManager));
        }
        return structureIcons;
    }

    private static NativeImage loadStructureIcon(Identifier iconId, ResourceManager builtinResourceManager, ResourceManager sampleResourceManager) {
        Identifier resolvedIconId = iconId == null ? UNKNOWN_STRUCTURE_ICON : iconId;
        Optional<Resource> resource = builtinResourceManager.getResource(resolvedIconId);
        if (resource.isEmpty()) {
            resource = sampleResourceManager.getResource(resolvedIconId);
        }
        if (resource.isEmpty()) {
            LOGGER.error("Failed to load structure icon: '{}'", resolvedIconId);
            resource = builtinResourceManager.getResource(UNKNOWN_STRUCTURE_ICON);
        }
        if (resource.isEmpty()) {
            LOGGER.error("FATAL ERROR LOADING: '{}' -- unable to load fallback!", resolvedIconId);
            return new NativeImage(16, 16, true);
        }
        return readIcon(resource.get(), resolvedIconId);
    }

    private static NativeImage loadRequiredIcon(ResourceManager resourceManager, Identifier iconId) {
        Optional<Resource> resource = resourceManager.getResource(iconId);
        if (resource.isEmpty()) {
            LOGGER.error("Failed to load required preview icon: '{}'", iconId);
            return new NativeImage(16, 16, true);
        }
        return readIcon(resource.get(), iconId);
    }

    private static NativeImage readIcon(Resource resource, Identifier iconId) {
        try (InputStream input = resource.open()) {
            return NativeImage.read(input);
        } catch (IOException e) {
            LOGGER.error("Unable to read preview icon: '{}'", iconId, e);
            return new NativeImage(16, 16, true);
        }
    }

    public record Assets(NativeImage[] structureIcons, NativeImage playerIcon, NativeImage spawnIcon) {
    }
}
