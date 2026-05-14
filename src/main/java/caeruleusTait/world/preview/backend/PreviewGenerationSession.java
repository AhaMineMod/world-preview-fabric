package caeruleusTait.world.preview.backend;

import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.sampler.ChunkSampler;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.backend.storage.PreviewStorageCacheManager;
import caeruleusTait.world.preview.backend.worker.SampleUtils;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.jetbrains.annotations.Nullable;

public record PreviewGenerationSession(
        long generationId,
        WorldOptions worldOptions,
        LevelStem levelStem,
        DimensionType dimensionType,
        ChunkGenerator chunkGenerator,
        ChunkSampler chunkSampler,
        SampleUtils sampleUtils,
        PreviewData previewData,
        PreviewStorageCacheManager cacheManager,
        @Nullable PreviewStorage previewStorage
) {
    public PreviewGenerationSession withPreviewStorage(PreviewStorage previewStorage) {
        return new PreviewGenerationSession(
                generationId,
                worldOptions,
                levelStem,
                dimensionType,
                chunkGenerator,
                chunkSampler,
                sampleUtils,
                previewData,
                cacheManager,
                previewStorage
        );
    }

    public int yMin() {
        return dimensionType.minY();
    }

    public int yMax() {
        return yMin() + dimensionType.height();
    }

    public ResourceManager sampleResourceManager() {
        return sampleUtils.resourceManager();
    }
}
