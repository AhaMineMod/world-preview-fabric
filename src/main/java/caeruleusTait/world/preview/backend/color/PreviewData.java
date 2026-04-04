package caeruleusTait.world.preview.backend.color;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.Object2ShortMap;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;

@SuppressWarnings("java:S6218")
public record PreviewData(
        BiomeData[] biomeId2BiomeData,
        StructureData[] structId2StructData,
        Object2ShortMap<String> biome2Id,
        Object2ShortMap<String> struct2Id,
        List<HeightmapPresetData> heightmapPresets,
        Map<String, ColorMap> colorMaps
) {
    public enum DataSource {
        MISSING,
        RESOURCE,
        CONFIG,
    }

    public record BiomeData(int id, Identifier tag, int color, int resourceOnlyColor, boolean isCave, boolean resourceOnlyIsCave, String name, String resourceOnlyName, DataSource dataSource) {
    }

    public record StructureData(int id, Identifier tag, String name, Identifier icon, Identifier item, boolean showByDefault, DataSource dataSource) {
    }

    public record HeightmapPresetData(String name, int minY, int maxY) {
        public static final Codec<HeightmapPresetData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("name").forGetter(HeightmapPresetData::name),
                Codec.INT.fieldOf("minY").forGetter(HeightmapPresetData::minY),
                Codec.INT.fieldOf("maxY").forGetter(HeightmapPresetData::maxY)
        ).apply(instance, HeightmapPresetData::new));
    }
}
