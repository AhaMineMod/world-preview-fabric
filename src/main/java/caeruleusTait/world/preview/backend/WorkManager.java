package caeruleusTait.world.preview.backend;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.sampler.ChunkSampler;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.backend.storage.PreviewStorageCacheManager;
import caeruleusTait.world.preview.backend.worker.PreviewWorkContext;
import caeruleusTait.world.preview.backend.worker.SampleContextFactory;
import caeruleusTait.world.preview.backend.worker.SampleUtils;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

public class WorkManager {
    public static final int Y_BLOCK_SHIFT = 3;
    public static final int Y_BLOCK_STRIDE = 1 << Y_BLOCK_SHIFT;
    private static final long CANCEL_WAIT_MS = 25L;

    private final RenderSettings renderSettings;
    private final WorldPreviewConfig config;
    private final WorkScheduler scheduler = new WorkScheduler();
    private final WorkPlanner planner;

    private final AtomicLong generationSequence = new AtomicLong(0L);
    private final AtomicInteger structureUnitsTotal = new AtomicInteger(0);
    private final AtomicInteger structureUnitsCompleted = new AtomicInteger(0);
    private final AtomicLong renderDataVersion = new AtomicLong(0L);

    private PreviewGenerationSession session;
    private ChunkPos lastQueuedTopLeft;
    private ChunkPos lastQueuedBotRight;
    private int lastY = Integer.MIN_VALUE;

    public WorkManager(RenderSettings renderSettings, WorldPreviewConfig config) {
        this.config = config;
        this.renderSettings = renderSettings;
        this.planner = new WorkPlanner(config, new Object());
    }

    public synchronized void changeWorldGenState(
            LevelStem levelStem,
            LayeredRegistryAccess<RegistryLayer> registryAccess,
            PreviewData previewData,
            WorldOptions worldOptions,
            WorldDataConfiguration worldDataConfiguration,
            PreviewStorageCacheManager previewStorageCacheManager,
            Proxy proxy,
            @Nullable Path tempDataPackDir,
            @Nullable MinecraftServer server
    ) {
        cancel();

        final DimensionType dimensionType = levelStem.type().value();
        final ChunkGenerator chunkGenerator = levelStem.generator();
        final BiomeSource biomeSource = chunkGenerator.getBiomeSource();
        final ChunkSampler chunkSampler = renderSettings.samplerType.create(renderSettings.quartStride());
        final LevelHeightAccessor levelHeightAccessor = LevelHeightAccessor.create(dimensionType.minY(), dimensionType.height());
        final SampleUtils sampleUtils;

        try {
            if (server == null) {
                sampleUtils = SampleContextFactory.createForPreview(
                        biomeSource,
                        chunkGenerator,
                        registryAccess,
                        worldOptions,
                        levelStem,
                        levelHeightAccessor,
                        worldDataConfiguration,
                        proxy,
                        tempDataPackDir
                );
            } else {
                sampleUtils = SampleContextFactory.createForServer(
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

        session = new PreviewGenerationSession(
                generationSequence.incrementAndGet(),
                worldOptions,
                levelStem,
                dimensionType,
                chunkGenerator,
                chunkSampler,
                sampleUtils,
                previewData,
                previewStorageCacheManager,
                null
        );
    }

    /**
     * MUST be called in the render thread and AFTER {@link #changeWorldGenState} has finished.
     */
    public synchronized void postChangeWorldGenState() {
        final PreviewGenerationSession activeSession = session;
        if (activeSession == null) {
            return;
        }

        // This call MAY change screens and MUST thus be called in the render thread!
        PreviewStorage previewStorage = activeSession.cacheManager().loadPreviewStorage(
                activeSession.worldOptions().seed(),
                activeSession.yMin(),
                activeSession.yMax()
        );

        session = activeSession.withPreviewStorage(previewStorage);
        scheduler.start(config.numThreads());
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
            LOGGER.warn("Unable to close preview sampling context", e);
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

    @SuppressWarnings("resource")
    public synchronized void cancel() {
        final PreviewGenerationSession activeSession = session;
        final WorkScheduler.ShutdownExecutors shutdownExecutors = scheduler.shutdownNow();

        final SampleUtils sampleUtilsToClose = activeSession == null ? null : activeSession.sampleUtils();
        final PreviewStorageCacheManager cacheManager = activeSession == null ? null : activeSession.cacheManager();
        final PreviewStorage storage = activeSession == null ? null : activeSession.previewStorage();
        final long seed = activeSession == null ? 0L : activeSession.worldOptions().seed();

        session = null;
        lastQueuedTopLeft = null;
        lastQueuedBotRight = null;
        lastY = Integer.MIN_VALUE;
        structureUnitsTotal.set(0);
        structureUnitsCompleted.set(0);
        renderDataVersion.incrementAndGet();

        if (shutdownExecutors.workerExecutor() != null
                || shutdownExecutors.queueExecutor() != null
                || sampleUtilsToClose != null
                || cacheManager != null) {
            finishCancelCleanup(
                    shutdownExecutors.workerExecutor(),
                    shutdownExecutors.queueExecutor(),
                    sampleUtilsToClose,
                    cacheManager,
                    storage,
                    seed
            );
        }
    }

    private boolean requeueOnYOnlyChange() {
        return !config.buildFullVertChunk;
    }

    public synchronized void queueRange(BlockPos topLeftBlock, BlockPos bottomRightBlock) {
        final PreviewGenerationSession activeSession = session;
        final PreviewStorage storage = activeSession == null ? null : activeSession.previewStorage();
        if (activeSession == null || storage == null || !scheduler.isStarted()) {
            return;
        }

        final ChunkPos topLeft = new ChunkPos(topLeftBlock);
        final ChunkPos bottomRight = new ChunkPos(bottomRightBlock);
        if (topLeft.equals(lastQueuedTopLeft)
                && bottomRight.equals(lastQueuedBotRight)
                && (topLeftBlock.getY() == lastY || !requeueOnYOnlyChange())) {
            return;
        }

        final boolean queued = scheduler.submitQueue(() -> queueRangeWrapper(activeSession, topLeftBlock, bottomRightBlock));
        if (!queued) {
            return;
        }

        lastQueuedTopLeft = topLeft;
        lastQueuedBotRight = bottomRight;
        lastY = topLeftBlock.getY();
    }

    private void queueRangeWrapper(PreviewGenerationSession activeSession, BlockPos topLeftBlock, BlockPos bottomRightBlock) {
        try {
            queueRangeReal(activeSession, topLeftBlock, bottomRightBlock);
        } catch (Throwable e) {
            LOGGER.error("Unhandled error while queueing preview range", e);
        }
    }

    private boolean isCurrentGeneration(long generationId) {
        final PreviewGenerationSession activeSession = session;
        return activeSession != null && activeSession.generationId() == generationId;
    }

    private void queueRangeReal(PreviewGenerationSession activeSession, BlockPos topLeftBlock, BlockPos bottomRightBlock) {
        if (!isCurrentGeneration(activeSession.generationId())) {
            return;
        }

        final Instant start = Instant.now();
        scheduler.cancelSubmittedBatchesAndWait();

        if (!isCurrentGeneration(activeSession.generationId())) {
            return;
        }

        final List<ChunkPos> chunks = ChunkPos.rangeClosed(new ChunkPos(topLeftBlock), new ChunkPos(bottomRightBlock)).toList();
        final PreviewWorkContext context = new PreviewWorkContext(
                activeSession.sampleUtils(),
                activeSession.previewStorage(),
                activeSession.previewData(),
                config,
                flags -> {
                    if (isCurrentGeneration(activeSession.generationId())) {
                        onWorkUnitCompleted(flags);
                    }
                }
        );

        WorkPlanner.PlanResult plan = planner.createPlan(
                activeSession,
                context,
                chunks,
                topLeftBlock.getY(),
                scheduler::shouldAbortQueuing,
                () -> isCurrentGeneration(activeSession.generationId())
        );

        structureUnitsTotal.set(plan.structureUnits());
        structureUnitsCompleted.set(0);
        scheduler.submitBatches(plan.batches());

        final Instant end = Instant.now();
        LOGGER.debug(
                "Queued {} chunks for generation using {} batches [{} ms] {}",
                plan.units(),
                plan.batches().size(),
                Duration.between(start, end).abs().toMillis(),
                plan.earlyAbort() ? "{early abort}" : ""
        );
    }

    public int yMin() {
        final PreviewGenerationSession activeSession = session;
        return activeSession == null ? 0 : activeSession.yMin();
    }

    public int yMax() {
        final PreviewGenerationSession activeSession = session;
        return activeSession == null ? 256 : activeSession.yMax();
    }

    public PreviewStorage previewStorage() {
        final PreviewGenerationSession activeSession = session;
        return activeSession == null ? null : activeSession.previewStorage();
    }

    @SuppressWarnings("unused")
    public boolean isSetup() {
        return scheduler.isStarted();
    }

    public WorldPreviewConfig config() {
        return config;
    }

    /**
     * This resource manager can access images in datapacks, while the
     * one provided in the GUI Minecraft class can't.
     */
    public ResourceManager sampleResourceManager() {
        return session.sampleResourceManager();
    }

    public SampleUtils sampleUtils() {
        return session.sampleUtils();
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
