package caeruleusTait.world.preview.backend.color;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

public final class DefaultPreviewMappingLoader {
    private static final String BIOME_COLORS = "data/c/worldgen/biome_colors.json";
    private static final String STRUCTURE_ICONS = "data/c/worldgen/structure_icons.json";
    private static final List<String> COLOR_MAPS = List.of(
            "cividis",
            "grayscale",
            "inferno",
            "magma",
            "plasma",
            "viridis"
    );
    private static final List<String> HEIGHTMAP_PRESETS = List.of("end", "overworld");

    private DefaultPreviewMappingLoader() {
    }

    public static void loadMissingDefaults(PreviewMappingData previewMappingData, Path userColorConfigFile) {
        if (!previewMappingData.hasBiomeMappings()) {
            loadBiomeColors(previewMappingData, userColorConfigFile);
        }
        if (!previewMappingData.hasStructureMappings()) {
            loadStructureIcons(previewMappingData);
        }
        if (!previewMappingData.hasColorMaps()) {
            loadColorMaps(previewMappingData);
        }
        if (!previewMappingData.hasHeightmapPresets()) {
            loadHeightmapPresets(previewMappingData);
        }
    }

    private static void loadBiomeColors(PreviewMappingData previewMappingData, Path userColorConfigFile) {
        JsonElement defaultColors = readBundledJson(BIOME_COLORS);
        if (defaultColors == null) {
            return;
        }

        previewMappingData.update(BiomeColorMapReloadListener.parseColorData(
                "c",
                defaultColors,
                PreviewData.DataSource.RESOURCE
        ));
        previewMappingData.makeBiomeResourceOnlyBackup();

        if (!Files.exists(userColorConfigFile)) {
            return;
        }

        try {
            JsonElement userColors = JsonParser.parseString(Files.readString(userColorConfigFile));
            previewMappingData.update(BiomeColorMapReloadListener.parseColorData(
                    "",
                    userColors,
                    PreviewData.DataSource.CONFIG
            ));
        } catch (IOException e) {
            LOGGER.warn("Unable to load fallback user biome color config from {}", userColorConfigFile, e);
        }
    }

    private static void loadStructureIcons(PreviewMappingData previewMappingData) {
        JsonElement defaultStructures = readBundledJson(STRUCTURE_ICONS);
        if (defaultStructures == null) {
            return;
        }

        previewMappingData.updateStruct(StructureMapReloadListener.parseStructureData(
                "c",
                defaultStructures,
                PreviewData.DataSource.RESOURCE
        ));
    }

    private static void loadColorMaps(PreviewMappingData previewMappingData) {
        for (String colorMap : COLOR_MAPS) {
            JsonElement jsonElement = readBundledJson("data/world_preview/colormap_preview/" + colorMap + ".json");
            if (jsonElement == null) {
                continue;
            }

            ColorMap.RawColorMap rawColorMap = ColorMap.RawColorMap.CODEC
                    .parse(JsonOps.INSTANCE, jsonElement)
                    .getOrThrow();
            previewMappingData.addColormap(new ColorMap(
                    Identifier.fromNamespaceAndPath("world_preview", colorMap),
                    rawColorMap
            ));
        }
    }

    private static void loadHeightmapPresets(PreviewMappingData previewMappingData) {
        for (String preset : HEIGHTMAP_PRESETS) {
            JsonElement jsonElement = readBundledJson("data/world_preview/heightmap_preview_presets/" + preset + ".json");
            if (jsonElement == null) {
                continue;
            }

            PreviewData.HeightmapPresetData heightmapPresetData = PreviewData.HeightmapPresetData.CODEC
                    .parse(JsonOps.INSTANCE, jsonElement)
                    .getOrThrow();
            previewMappingData.addHeightmapPreset(heightmapPresetData);
        }
    }

    private static JsonElement readBundledJson(String path) {
        ClassLoader classLoader = DefaultPreviewMappingLoader.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(path)) {
            if (input == null) {
                LOGGER.warn("Bundled preview mapping resource is missing: {}", path);
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader);
            }
        } catch (IOException e) {
            LOGGER.warn("Unable to read bundled preview mapping resource: {}", path, e);
            return null;
        }
    }
}
