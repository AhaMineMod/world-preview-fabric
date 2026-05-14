package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.backend.sampler.ChunkSampler;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.List;

public class LayerChunkWorkUnit extends WorkUnit {
    private final ChunkSampler sampler;

    public LayerChunkWorkUnit(ChunkSampler sampler, ChunkPos pos, PreviewWorkContext context, int y) {
        super(context, pos, y);
        this.sampler = sampler;
    }

    @Override
    protected List<WorkResult> doWork() {
        if (sampleUtils.hasRawNoiseInfo()) {
            return doRawNoiseWork();
        } else {
            return doNormalWork();
        }
    }

    private List<WorkResult> doNormalWork() {
        List<WorkResult> results = NoiseSampleWriter.createResults(this, y, primarySection, false);
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        sampler.forEachBlock(chunkPos, y, cursor, p ->
                NoiseSampleWriter.writeSample(sampler, p, biomeIdFrom(sampleUtils.sampleBiome(p)), null, results)
        );
        return results;
    }

    private List<WorkResult> doRawNoiseWork() {
        List<WorkResult> results = NoiseSampleWriter.createResults(this, y, primarySection, true);
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final short[] noiseData = new short[NoiseSampleWriter.NOISE_FIELD_COUNT];
        sampler.forEachBlock(chunkPos, y, cursor, p ->
                NoiseSampleWriter.writeSample(sampler, p, biomeIdFrom(sampleUtils.sampleBiomeAndNoise(p, noiseData)), noiseData, results)
        );
        return results;
    }

    @Override
    public long flags() {
        return PreviewStorage.FLAG_BIOME;
    }
}
