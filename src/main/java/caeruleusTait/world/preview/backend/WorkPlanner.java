package caeruleusTait.world.preview.backend;

import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.storage.PreviewSection;
import caeruleusTait.world.preview.backend.worker.FullChunkWorkUnit;
import caeruleusTait.world.preview.backend.worker.HeightmapWorkUnit;
import caeruleusTait.world.preview.backend.worker.IntersectionWorkUnit;
import caeruleusTait.world.preview.backend.worker.LayerChunkWorkUnit;
import caeruleusTait.world.preview.backend.worker.PreviewWorkContext;
import caeruleusTait.world.preview.backend.worker.SlowHeightmapWorkUnit;
import caeruleusTait.world.preview.backend.worker.SlowIntersectionWorkUnit;
import caeruleusTait.world.preview.backend.worker.StructStartWorkUnit;
import caeruleusTait.world.preview.backend.worker.WorkBatch;
import caeruleusTait.world.preview.backend.worker.WorkUnit;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

public class WorkPlanner {
    private final WorldPreviewConfig config;
    private final Object completedSynchro;
    private final SplittableRandom random = new SplittableRandom();

    public WorkPlanner(WorldPreviewConfig config, Object completedSynchro) {
        this.config = config;
        this.completedSynchro = completedSynchro;
    }

    @SuppressWarnings("resource")
    public PlanResult createPlan(
            PreviewGenerationSession session,
            PreviewWorkContext context,
            List<ChunkPos> chunks,
            int y,
            BooleanSupplier shouldAbort,
            BooleanSupplier shouldApplyResults
    ) {
        List<WorkBatch> batches = new ArrayList<>();
        int units = 0;

        units += queueForLevel(
                batches,
                chunks,
                y,
                4096,
                (pos, levelY) -> workUnitFactory(session, context, pos, levelY),
                shouldApplyResults
        );

        final int structureUnits;
        if (config.sampleStructures && !shouldAbort.getAsBoolean()) {
            structureUnits = queueForLevel(
                    batches,
                    chunks,
                    0,
                    1,
                    (pos, levelY) -> new StructStartWorkUnit(context, pos),
                    shouldApplyResults
            );
            units += structureUnits;
        } else {
            structureUnits = 0;
        }

        final int sectionSizeExponent = PreviewSection.SHIFT - PreviewSection.QUART_TO_SECTION_SHIFT;
        final int numChunksPerSection = PreviewSection.SECTION_SIZE >> (sectionSizeExponent - 4);

        if (config.sampleHeightmap && !shouldAbort.getAsBoolean() && session.sampleUtils().noiseGeneratorSettings() != null) {
            units += queueForLevel(
                    batches,
                    uniqueSectionAlignedChunks(chunks),
                    0,
                    1,
                    (pos, levelY) -> new HeightmapWorkUnit(session.chunkSampler(), context, pos, numChunksPerSection),
                    shouldApplyResults
            );
        } else if (config.sampleHeightmap && !shouldAbort.getAsBoolean()) {
            units += queueForLevel(
                    batches,
                    chunks,
                    0,
                    64,
                    (pos, levelY) -> new SlowHeightmapWorkUnit(session.chunkSampler(), context, pos),
                    shouldApplyResults
            );
        }

        if (config.sampleIntersections && !shouldAbort.getAsBoolean() && session.sampleUtils().noiseGeneratorSettings() != null) {
            units += queueForLevel(
                    batches,
                    uniqueSectionAlignedChunks(chunks),
                    0,
                    1,
                    (pos, levelY) -> new IntersectionWorkUnit(
                            session.chunkSampler(),
                            context,
                            pos,
                            numChunksPerSection,
                            WorkManager.Y_BLOCK_STRIDE
                    ),
                    shouldApplyResults
            );
        } else if (config.sampleIntersections && !shouldAbort.getAsBoolean()) {
            units += queueForLevel(
                    batches,
                    chunks,
                    0,
                    64,
                    (pos, levelY) -> new SlowIntersectionWorkUnit(
                            session.chunkSampler(),
                            context,
                            pos,
                            session.yMin(),
                            session.yMax(),
                            WorkManager.Y_BLOCK_STRIDE
                    ),
                    shouldApplyResults
            );
        }

        if (config.backgroundSampleVertChunk && !config.buildFullVertChunk) {
            for (int adjacentY : genAdjacentYLevels(session, y)) {
                if (shouldAbort.getAsBoolean()) {
                    break;
                }
                units += queueForLevel(
                        batches,
                        chunks,
                        adjacentY,
                        4096,
                        (pos, levelY) -> workUnitFactory(session, context, pos, levelY),
                        shouldApplyResults
                );
            }
        }

        return new PlanResult(batches, units, structureUnits, shouldAbort.getAsBoolean());
    }

    private WorkUnit workUnitFactory(PreviewGenerationSession session, PreviewWorkContext context, ChunkPos pos, int y) {
        if (config.buildFullVertChunk) {
            return new FullChunkWorkUnit(session.chunkSampler(), pos, context, session.yMin(), session.yMax(), WorkManager.Y_BLOCK_STRIDE);
        }
        return new LayerChunkWorkUnit(session.chunkSampler(), pos, context, y);
    }

    private int queueForLevel(
            List<WorkBatch> batches,
            List<ChunkPos> chunks,
            int y,
            int maxBatchSize,
            BiFunction<ChunkPos, Integer, WorkUnit> workUnitFactory,
            BooleanSupplier shouldApplyResults
    ) {
        WorkUnit[] toQueue = new WorkUnit[chunks.size()];
        int size = 0;
        synchronized (completedSynchro) {
            for (ChunkPos chunkPos : chunks) {
                WorkUnit workUnit = workUnitFactory.apply(chunkPos, y);
                if (!workUnit.isCompleted()) {
                    toQueue[size++] = workUnit;
                }
            }
        }

        if (size == 0) {
            return 0;
        }

        for (int i = size - 1; i > 1; --i) {
            int randomIndexToSwap = random.nextInt(size);
            WorkUnit temp = toQueue[randomIndexToSwap];
            toQueue[randomIndexToSwap] = toQueue[i];
            toQueue[i] = temp;
        }

        final List<WorkUnit> queuedUnits = Arrays.asList(toQueue);
        int batchSize = maxBatchSize == 1 ? 1 : Math.clamp(size / 4096, 8, maxBatchSize);
        for (int i = 0; i < size; i += batchSize) {
            int end = Math.min(i + batchSize, size);
            batches.add(new WorkBatch(queuedUnits.subList(i, end), completedSynchro, shouldApplyResults));
        }

        return size;
    }

    private List<ChunkPos> uniqueSectionAlignedChunks(List<ChunkPos> chunks) {
        LongSet queuedChunks = new LongOpenHashSet(chunks.size());
        List<ChunkPos> alignedChunks = new ArrayList<>(chunks.size());
        for (ChunkPos chunkPos : chunks) {
            ChunkPos shifted = new ChunkPos((chunkPos.x >> 4) << 4, (chunkPos.z >> 4) << 4);
            if (queuedChunks.add(shifted.toLong())) {
                alignedChunks.add(shifted);
            }
        }
        return alignedChunks;
    }

    private List<Integer> genAdjacentYLevels(PreviewGenerationSession session, int y) {
        final int yMin = session.yMin();
        final int yMax = session.yMax();
        final List<Integer> res = new ArrayList<>();

        final int max = session.dimensionType().height() / WorkManager.Y_BLOCK_STRIDE + 1;
        for (int i = 1; i <= max; ++i) {
            int y1 = y + i * WorkManager.Y_BLOCK_STRIDE;
            int y2 = y - i * WorkManager.Y_BLOCK_STRIDE;
            if (y2 >= yMin) {
                res.add(y2);
            }
            if (y1 <= yMax) {
                res.add(y1);
            }
            if (y1 > yMax && y2 < yMin) {
                break;
            }
        }

        return res;
    }

    public record PlanResult(List<WorkBatch> batches, int units, int structureUnits, boolean earlyAbort) {
    }
}
