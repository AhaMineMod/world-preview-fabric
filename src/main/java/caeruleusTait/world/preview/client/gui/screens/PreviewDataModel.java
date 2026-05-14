package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.backend.color.PreviewData;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public final class PreviewDataModel {
    private PreviewDataModel() {
    }

    public static List<Identifier> sortedLevelStemKeys(Registry<LevelStem> levelStemRegistry) {
        return levelStemRegistry.keySet().stream().sorted(Comparator.comparing(Object::toString)).toList();
    }

    public static Set<Identifier> collectBiomeTags(Registry<Biome> biomeRegistry, List<TagKey<Biome>> tags) {
        return tags.stream()
                .flatMap(tagKey -> StreamSupport.stream(biomeRegistry.getTagOrEmpty(tagKey).spliterator(), false))
                .map(holder -> holder.unwrapKey().orElseThrow().identifier())
                .collect(Collectors.toSet());
    }

    public static Set<Identifier> collectStructureTags(Registry<Structure> structureRegistry, TagKey<Structure> tagKey) {
        return StreamSupport.stream(structureRegistry.getTagOrEmpty(tagKey).spliterator(), false)
                .map(holder -> holder.unwrapKey().orElseThrow().identifier())
                .collect(Collectors.toSet());
    }

    public static List<String> missingBiomes(PreviewData previewData) {
        return Arrays.stream(previewData.biomeId2BiomeData())
                .filter(x -> x.dataSource() == PreviewData.DataSource.MISSING)
                .map(PreviewData.BiomeData::tag)
                .map(Identifier::toString)
                .toList();
    }

    public static List<String> missingStructures(PreviewData previewData) {
        return Arrays.stream(previewData.structId2StructData())
                .filter(x -> x.dataSource() == PreviewData.DataSource.MISSING)
                .map(PreviewData.StructureData::tag)
                .map(Identifier::toString)
                .toList();
    }
}
