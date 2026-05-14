package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.backend.sampler.ChunkSampler;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

public class FullChunkWorkUnit extends WorkUnit {
    private final ChunkSampler sampler;
    private final int yMin;
    private final int yMax;
    private final int yStride;

    public FullChunkWorkUnit(ChunkSampler sampler, ChunkPos pos, PreviewWorkContext context, int yMin, int yMax, int yStride) {
        super(context, pos, 0);
        this.sampler = sampler;
        this.yMin = yMin;
        this.yMax = yMax;
        this.yStride = yStride;
    }

    @Override
    protected List<WorkResult> doWork() {
        if (sampleUtils.hasRawNoiseInfo()) {
            return doRawNoiseWork();
        } else {
            return doNormalWork();
        }
    }

    private List<WorkResult> doRawNoiseWork() {
        List<WorkResult> results = new ArrayList<>(((yMax - yMin) / yStride) * 7);
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final short[] noiseData = new short[NoiseSampleWriter.NOISE_FIELD_COUNT];
        for (int y = yMin; y <= yMax; y += yStride) {
            List<WorkResult> yResults = NoiseSampleWriter.createResults(
                    this,
                    y,
                    y == this.y ? primarySection : storage.section4(chunkPos, y, flags()),
                    true
            );
            sampler.forEachBlock(chunkPos, y, cursor, p ->
                    NoiseSampleWriter.writeSample(sampler, p, biomeIdFrom(sampleUtils.sampleBiomeAndNoise(p, noiseData)), noiseData, yResults)
            );
            results.addAll(yResults);
        }
        return results;
    }

    private List<WorkResult> doNormalWork() {
        List<WorkResult> results = new ArrayList<>((yMax - yMin) / yStride);
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = yMin; y <= yMax; y += yStride) {
            List<WorkResult> yResults = NoiseSampleWriter.createResults(
                    this,
                    y,
                    y == this.y ? primarySection : storage.section4(chunkPos, y, flags()),
                    false
            );
            sampler.forEachBlock(chunkPos, y, cursor, p ->
                    NoiseSampleWriter.writeSample(sampler, p, biomeIdFrom(sampleUtils.sampleBiome(p)), null, yResults)
            );
            results.addAll(yResults);
        }
        return results;
    }

    @Override
    public long flags() {
        return PreviewStorage.FLAG_BIOME;
    }

}
