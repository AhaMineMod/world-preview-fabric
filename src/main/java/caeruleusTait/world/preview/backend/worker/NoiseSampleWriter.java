package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.backend.sampler.ChunkSampler;
import caeruleusTait.world.preview.backend.storage.PreviewSection;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;

import java.util.ArrayList;
import java.util.List;

final class NoiseSampleWriter {
    static final int NOISE_FIELD_COUNT = 6;
    private static final int EXPECTED_VALUES = 16;

    private static final long[] NOISE_FLAGS = new long[]{
            PreviewStorage.FLAG_NOISE_TEMPERATURE,
            PreviewStorage.FLAG_NOISE_HUMIDITY,
            PreviewStorage.FLAG_NOISE_CONTINENTALNESS,
            PreviewStorage.FLAG_NOISE_EROSION,
            PreviewStorage.FLAG_NOISE_DEPTH,
            PreviewStorage.FLAG_NOISE_WEIRDNESS
    };

    private NoiseSampleWriter() {
    }

    static List<WorkResult> createResults(WorkUnit unit, int y, PreviewSection biomeSection, boolean includeNoise) {
        WorkResult biomeResult = new WorkResult(unit, QuartPos.fromBlock(y), biomeSection, new WorkResult.BlockResults(EXPECTED_VALUES), List.of());
        if (!includeNoise) {
            return List.of(biomeResult);
        }

        List<WorkResult> results = new ArrayList<>(NOISE_FLAGS.length + 1);
        results.add(biomeResult);
        for (long noiseFlag : NOISE_FLAGS) {
            results.add(new WorkResult(
                    unit,
                    QuartPos.fromBlock(y),
                    unit.storage.section4(unit.chunkPos, y, noiseFlag),
                    new WorkResult.BlockResults(EXPECTED_VALUES),
                    List.of()
            ));
        }
        return results;
    }

    static void writeSample(ChunkSampler sampler, BlockPos pos, short biomeId, short[] noiseResult, List<WorkResult> results) {
        sampler.expandRaw(pos, biomeId, results.getFirst());
        if (noiseResult == null) {
            return;
        }
        final int noiseCount = Math.min(noiseResult.length, results.size() - 1);
        for (int i = 0; i < noiseCount; ++i) {
            sampler.expandRaw(pos, noiseResult[i], results.get(i + 1));
        }
    }
}
