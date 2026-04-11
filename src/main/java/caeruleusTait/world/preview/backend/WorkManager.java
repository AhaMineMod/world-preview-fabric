package caeruleusTait.world.preview.backend;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.sampler.ChunkSampler;
import caeruleusTait.world.preview.backend.storage.PreviewSection;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.backend.storage.PreviewStorageCacheManager;
import caeruleusTait.world.preview.backend.worker.FullChunkWorkUnit;
import caeruleusTait.world.preview.backend.worker.HeightmapWorkUnit;
import caeruleusTait.world.preview.backend.worker.IntersectionWorkUnit;
import caeruleusTait.world.preview.backend.worker.LayerChunkWorkUnit;
import caeruleusTait.world.preview.backend.worker.SampleUtils;
import caeruleusTait.world.preview.backend.worker.SlowHeightmapWorkUnit;
import caeruleusTait.world.preview.backend.worker.SlowIntersectionWorkUnit;
import caeruleusTait.world.preview.backend.worker.StructStartWorkUnit;
import caeruleusTait.world.preview.backend.worker.WorkBatch;
import caeruleusTait.world.preview.backend.worker.WorkUnit;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

public class WorkManager {
    public static final int Y_BLOCK_SHIFT = 3;
    public static final int Y_BLOCK_STRIDE = 1 << Y_BLOCK_SHIFT;
    private static final long CANCEL_WAIT_MS = 25L;

    private final Object completedSynchro = new Object();

    private WorldOptions worldOptions;
    private LevelStem levelStem;
    private DimensionType dimensionType;
    private ChunkGenerator chunkGenerator;
    private ChunkSampler chunkSampler;
    private SampleUtils sampleUtils;

    private PreviewData previewData;
    private PreviewStorage previewStorage;
    private PreviewStorageCacheManager previewStorageCacheManager;
    private final RenderSettings renderSettings;
    private final WorldPreviewConfig config;

    private final List<WorkBatch> currentBatches = new ArrayList<>();
    private final List<Future<?>> futures = new ArrayList<>();
    private final List<Future<?>> queueFutures = new ArrayList<>();
    private final SplittableRandom random = new SplittableRandom();

    private ExecutorService executorService;
    private ExecutorService queueChunksService;

    private ChunkPos lastQueuedTopLeft;
    private ChunkPos lastQueuedBotRight;
    private int lastY;

    private boolean queueIsRunning = false;
    private boolean shouldEarlyAbortQueuing = false;
    private final AtomicInteger structureUnitsTotal = new AtomicInteger(0);
    private final AtomicInteger structureUnitsCompleted = new AtomicInteger(0);
    private final AtomicLong renderDataVersion = new AtomicLong(0L);

    public WorkManager(RenderSettings renderSettings, WorldPreviewConfig config) {
        this.config = config;
        this.renderSettings = renderSettings;
    }

    public synchronized void changeWorldGenState(
            LevelStem _levelStem,
            LayeredRegistryAccess<RegistryLayer> _registryAccess,
            PreviewData _previewData,
            WorldOptions _worldOptions,
            WorldDataConfiguration _worldDataConfiguration,
            PreviewStorageCacheManager _previewStorageCacheManager,
            Proxy proxy,
            @Nullable Path tempDataPackDir,
            @Nullable MinecraftServer server
    ) {
        cancel();
        worldOptions = _worldOptions;
        levelStem = _levelStem;
        dimensionType = levelStem.type().value();
        chunkGenerator = levelStem.generator();
        final BiomeSource biomeSource = chunkGenerator.getBiomeSource();
        previewStorageCacheManager = _previewStorageCacheManager;
        chunkSampler = renderSettings.samplerType.create(renderSettings.quartStride());
        previewData = _previewData;

        LevelHeightAccessor levelHeightAccessor = LevelHeightAccessor.create(dimensionType.minY(), dimensionType.height());
        try {
            if (server == null) {
                sampleUtils = new SampleUtils(
                        biomeSource,
                        chunkGenerator,
                        _registryAccess,
                        worldOptions,
                        levelStem,
                        levelHeightAccessor,
                        _worldDataConfiguration,
                        proxy,
                        tempDataPackDir
                );
            } else {
                sampleUtils = new SampleUtils(
                        server,
                        biomeSource,
                        chunkGenerator,
                        worldOptions,
                        levelStem,
                        levelHeightAccessor
                );
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * MUST be called in the render thread and AFTER {@link #changeWorldGenState} has finished
     */
    public void postChangeWorldGenState() {
        // This call MAY change screens and MUST thus be called in the render thread!
        previewStorage = previewStorageCacheManager.loadPreviewStorage(worldOptions.seed(), yMin(), yMax());

        // Only create the executors at the end to ensure that there are no
        // null pointer exceptions
        executorService = Executors.newFixedThreadPool(config.numThreads());
        queueChunksService = Executors.newSingleThreadExecutor();
    }

    private void cancelOutstandingWork() {
        shouldEarlyAbortQueuing = true;

        synchronized (currentBatches) {
            currentBatches.forEach(WorkBatch::cancel);
            currentBatches.clear();
        }

        List<Future<?>> allFutures = new ArrayList<>();
        synchronized (futures) {
            allFutures.addAll(queueFutures);
            allFutures.addAll(futures);
            queueFutures.clear();
            futures.clear();
        }
        allFutures.forEach(f -> f.cancel(true));
    }

    private static boolean awaitTermination(@Nullable ExecutorService executorService) {
        if (executorService == null) {
            return true;
        }
        try {
            return executorService.awaitTermination(CANCEL_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void awaitTerminationUninterruptibly(@Nullable ExecutorService executorService) {
        if (executorService == null) {
            return;
        }
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (executorService.awaitTermination(1L, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeSampleUtils(@Nullable SampleUtils sampleUtils) {
        if (sampleUtils == null) {
            return;
        }
        try {
            sampleUtils.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void finishCancelCleanup(
            @Nullable ExecutorService executorService,
            @Nullable ExecutorService queueChunksService,
            @Nullable SampleUtils sampleUtils,
            @Nullable PreviewStorageCacheManager cacheManager,
            @Nullable PreviewStorage storage,
            long seed
    ) {
        Runnable cleanup = () -> {
            awaitTerminationUninterruptibly(executorService);
            awaitTerminationUninterruptibly(queueChunksService);
            closeSampleUtils(sampleUtils);
            if (cacheManager != null) {
                cacheManager.storePreviewStorageAsync(seed, storage);
            }
        };

        final boolean workersStopped = awaitTermination(executorService)
                && awaitTermination(queueChunksService);
        if (workersStopped) {
            cleanup.run();
            return;
        }
        CompletableFuture.runAsync(cleanup);
    }

    public synchronized void cancel() {
        final ExecutorService workerExecutor = this.executorService;
        final ExecutorService queueExecutor = this.queueChunksService;
        if (workerExecutor != null) {
            cancelOutstandingWork();
            workerExecutor.shutdownNow();
            if (queueExecutor != null) {
                queueExecutor.shutdownNow();
            }
        }

        final SampleUtils sampleUtilsToClose = this.sampleUtils;
        final PreviewStorageCacheManager cacheManager = this.previewStorageCacheManager;
        final PreviewStorage storage = this.previewStorage;
        final long seed = worldOptions == null ? 0L : worldOptions.seed();

        worldOptions = null;
        levelStem = null;
        dimensionType = null;
        chunkGenerator = null;
        this.sampleUtils = null;
        previewStorage = null;
        lastQueuedTopLeft = null;
        lastQueuedBotRight = null;
        lastY = Integer.MIN_VALUE;
        queueIsRunning = false;
        futures.clear();
        queueFutures.clear();
        this.executorService = null;
        this.queueChunksService = null;
        previewStorageCacheManager = null;
        structureUnitsTotal.set(0);
        structureUnitsCompleted.set(0);
        renderDataVersion.incrementAndGet();

        if (workerExecutor != null || queueExecutor != null || sampleUtilsToClose != null || cacheManager != null) {
            finishCancelCleanup(workerExecutor, queueExecutor, sampleUtilsToClose, cacheManager, storage, seed);
        }
    }

    private boolean requeueOnYOnlyChange() {
        return !config.buildFullVertChunk;
    }

    public void queueRange(BlockPos topLeftBlock, BlockPos bottomRightBlock) {
        final ExecutorService localExecutor = executorService;
        final ExecutorService localQueueExecutor = queueChunksService;
        final ChunkPos topLeft = new ChunkPos(topLeftBlock);
        final ChunkPos bottomRight = new ChunkPos(bottomRightBlock);
        if (localExecutor == null
                || localQueueExecutor == null
                || sampleUtils == null
                || previewStorage == null
                || previewData == null
                ||
                (
                        topLeft.equals(lastQueuedTopLeft)
                        && bottomRight.equals(lastQueuedBotRight)
                        && (topLeftBlock.getY() == lastY || !requeueOnYOnlyChange())
                )
        ) {
            return;
        }

        // Only have one in queue
        if (queueIsRunning) {
            // Signal the current queue algorithm to hurry up and skip
            // queueing more work units / batches since they will be canceled
            // the next run anyway.
            shouldEarlyAbortQueuing = true;
            return;
        }

        // Now, that we are definitely queueing, remember the last values
        lastQueuedTopLeft = topLeft;
        lastQueuedBotRight = bottomRight;
        lastY = topLeftBlock.getY();
        synchronized (futures) {
            queueFutures.add(localQueueExecutor.submit(() -> queueRangeWrapper(topLeftBlock, bottomRightBlock)));
        }
    }

    private void queueRangeWrapper(BlockPos topLeftBlock, BlockPos bottomRightBlock) {
        queueIsRunning = true;
        shouldEarlyAbortQueuing = false;
        try {
            queueRangeReal(topLeftBlock, bottomRightBlock);
        } catch (Throwable e) {
            LOGGER.error("Unhandled error while queueing preview range", e);
        } finally {
            queueIsRunning = false;
        }
    }

    public void queueRangeReal(BlockPos topLeftBlock, BlockPos bottomRightBlock) {
        if (executorService == null || sampleUtils == null || previewStorage == null || previewData == null) {
            return;
        }
        final Instant start = Instant.now();
        final ChunkPos topLeft = new ChunkPos(topLeftBlock);
        final ChunkPos bottomRight = new ChunkPos(bottomRightBlock);

        // Cancel current batches
        synchronized (currentBatches) {
            currentBatches.forEach(WorkBatch::cancel);
            currentBatches.clear();
        }
        synchronized (futures) {
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (CancellationException ignored) {
                    // Work was canceled while tearing down; expected during screen/world close.
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }
            futures.clear();
        }

        if (executorService == null || sampleUtils == null || previewStorage == null || previewData == null) {
            return;
        }

        // Calculate new batches
        final List<ChunkPos> chunks = ChunkPos.rangeClosed(topLeft, bottomRight).toList();
        int units = 0;

        // Main biomes
        final int mainBiomeUnits = queueForLevel(chunks, topLeftBlock.getY(), 4096, this::workUnitFactory);
        units += mainBiomeUnits;

        // Structures
        final int structureUnits;
        if (config.sampleStructures && !shouldEarlyAbortQueuing) {
            // Keep structure tasks fine-grained to improve parallelism and responsiveness while panning.
            structureUnits = queueForLevel(chunks, 0, 1, (pos, y) -> new StructStartWorkUnit(sampleUtils, pos, previewData));
            units += structureUnits;
        } else {
            structureUnits = 0;
        }
        structureUnitsTotal.set(structureUnits);
        structureUnitsCompleted.set(0);

        final int sectionSizeExponent = PreviewSection.SHIFT - PreviewSection.QUART_TO_SECTION_SHIFT;
        final int numChunksPerSection = PreviewSection.SECTION_SIZE >> (sectionSizeExponent - 4);

        // Height map
        if (config.sampleHeightmap && !shouldEarlyAbortQueuing && sampleUtils.noiseGeneratorSettings() != null) {
            List<ChunkPos> heightMapChunks = uniqueSectionAlignedChunks(chunks);
            final int heightUnits = queueForLevel(heightMapChunks, 0, 1, (pos, y) -> new HeightmapWorkUnit(chunkSampler, sampleUtils, pos, numChunksPerSection, previewData));
            units += heightUnits;
        } else if (config.sampleHeightmap && !shouldEarlyAbortQueuing) {
            final int heightUnits = queueForLevel(chunks, 0, 64, (pos, y) -> new SlowHeightmapWorkUnit(chunkSampler, sampleUtils, pos, previewData));
            units += heightUnits;
        }

        // Intersections
        if (config.sampleIntersections && !shouldEarlyAbortQueuing && sampleUtils.noiseGeneratorSettings() != null) {
            List<ChunkPos> intersectChunks = uniqueSectionAlignedChunks(chunks);
            final int intersectUnits = queueForLevel(intersectChunks, 0, 1, (pos, y) -> new IntersectionWorkUnit(chunkSampler, sampleUtils, pos, numChunksPerSection, previewData, Y_BLOCK_STRIDE));
            units += intersectUnits;
        } else if (config.sampleIntersections && !shouldEarlyAbortQueuing) {
            final int intersectUnits = queueForLevel(chunks, 0, 64, (pos, y) -> new SlowIntersectionWorkUnit(chunkSampler, sampleUtils, pos, previewData, yMin(), yMax(), Y_BLOCK_STRIDE));
            units += intersectUnits;
        }

        // Now sample adjacent levels
        if (config.backgroundSampleVertChunk && !config.buildFullVertChunk) {
            for (int y : genAdjacentYLevels(topLeftBlock.getY())) {
                if (shouldEarlyAbortQueuing) {
                    break;
                }
                final int layerUnits = queueForLevel(chunks, y, 4096, this::workUnitFactory);
                units += layerUnits;
            }
        }

        /* Compression debug code
        if (units == 0) {
            List<Short> x = previewStorage.compressionStatistics();
            LOGGER.info("Compression statistics: {}", Arrays.toString(x.toArray()));
        }
         */

        final Instant end = Instant.now();
        LOGGER.debug(
                "Queued {} chunks for generation using {} batches [{} ms] {}",
                units,
                currentBatches.size(),
                Duration.between(start, end).abs().toMillis(),
                shouldEarlyAbortQueuing ? "{early abort}" : ""
        );
    }

    private WorkUnit workUnitFactory(ChunkPos pos, int y) {
        if (config.buildFullVertChunk) {
            return new FullChunkWorkUnit(chunkSampler, pos, sampleUtils, previewData, yMin(), yMax(), Y_BLOCK_STRIDE);
        } else {
            return new LayerChunkWorkUnit(chunkSampler, pos, sampleUtils, previewData, y);
        }
    }

    private int queueForLevel(List<ChunkPos> chunks, int y, int maxBatchSize, BiFunction<ChunkPos, Integer, WorkUnit> workUnitFactoryFunc) {
        WorkUnit[] toQueue = new WorkUnit[chunks.size()];
        int size = 0;
        synchronized (completedSynchro) {
            for (ChunkPos chunkPos : chunks) {
                WorkUnit workUnit = workUnitFactoryFunc.apply(chunkPos, y);
                if (workUnit.isCompleted()) {
                    continue;
                }
                toQueue[size++] = workUnit;
            }
        }

        if (size == 0) {
            return 0;
        }

        // Add some randomness
        for (int i = size - 1; i > 1; --i) {
            int randomIndexToSwap = random.nextInt(size);
            WorkUnit temp = toQueue[randomIndexToSwap];
            toQueue[randomIndexToSwap] = toQueue[i];
            toQueue[i] = temp;
        }

        // Batch to reduce threading overhead
        int batchSize = maxBatchSize == 1 ? 1 : Math.clamp(size / 4096, 8, maxBatchSize);
        WorkBatch[] batches = new WorkBatch[batchSize == 1 ? size : (size / batchSize) + 1];
        if (batchSize > 1) {
            int batchIdx = 0;
            batches[batchIdx] = new WorkBatch(new ArrayList<>(batchSize), completedSynchro, previewData);
            for (int i = 0; i < size; ++i) {
                batches[batchIdx].workUnits.add(toQueue[i]);
                if (batches[batchIdx].workUnits.size() >= batchSize) {
                    batches[++batchIdx] = new WorkBatch(new ArrayList<>(batchSize), completedSynchro, previewData);
                }
            }
        } else {
            for (int i = 0; i < size; ++i) {
                batches[i] = new WorkBatch(List.of(toQueue[i]), completedSynchro, previewData);
            }
        }

        // Submit and store
        synchronized (futures) {
            for (WorkBatch batch : batches) {
                futures.add(executorService.submit(batch::process));
            }
        }
        synchronized (currentBatches) {
            currentBatches.addAll(Arrays.asList(batches));
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

    private List<Integer> genAdjacentYLevels(int y) {
        final int yMin = yMin();
        final int yMax = yMax();

        final List<Integer> res = new ArrayList<>();

        final int max = dimensionType.height() / Y_BLOCK_STRIDE + 1; // Full height
        for (int i = 1; i <= max; ++i) {
            int y1 = y + i * Y_BLOCK_STRIDE;
            int y2 = y - i * Y_BLOCK_STRIDE;
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

    public int yMin() {
        return dimensionType == null ? 0 : dimensionType.minY();
    }

    public int yMax() {
        return yMin() + (dimensionType == null ? 256 : dimensionType.height());
    }

    public PreviewStorage previewStorage() {
        return previewStorage;
    }

    public boolean isSetup() {
        return executorService != null;
    }

    public WorldPreviewConfig config() {
        return config;
    }

    /**
     * This resource manager can access images in datapacks, while the
     * one provided in the GUI Minecraft class can't.
     */
    public ResourceManager sampleResourceManager() {
        return sampleUtils.resourceManager();
    }

    public SampleUtils sampleUtils() {
        return sampleUtils;
    }

    public void onWorkUnitCompleted(long flags) {
        renderDataVersion.incrementAndGet();
        if ((flags & PreviewStorage.FLAG_STRUCT_START) != 0L) {
            final int total = structureUnitsTotal.get();
            if (total <= 0) {
                return;
            }
            structureUnitsCompleted.updateAndGet(x -> Math.min(total, x + 1));
        }
    }

    public float structureGenerationProgress() {
        if (!config.sampleStructures) {
            return 1.0f;
        }
        final int total = structureUnitsTotal.get();
        if (total <= 0) {
            return 1.0f;
        }
        return Math.clamp(structureUnitsCompleted.get() / (float) total, 0.0f, 1.0f);
    }

    public long renderDataVersion() {
        return renderDataVersion.get();
    }
}
