package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.storage.PreviewSection;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;

import java.util.List;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

public abstract class WorkUnit {
    protected final PreviewWorkContext context;
    protected final SampleUtils sampleUtils;
    protected final PreviewStorage storage;
    protected final PreviewSection primarySection;
    protected final ChunkPos chunkPos;
    protected final PreviewData previewData;
    protected final WorldPreviewConfig config;
    protected final int y;
    private volatile boolean isCanceled;

    protected WorkUnit(PreviewWorkContext context, ChunkPos chunkPos, int y) {
        this.context = context;
        this.sampleUtils = context.sampleUtils();
        this.chunkPos = chunkPos;
        this.previewData = context.previewData();
        this.config = context.config();
        this.y = y;

        this.storage = context.storage();
        if (this.storage == null) {
            // Can happen during teardown race while queued work is still being constructed.
            this.primarySection = null;
            this.isCanceled = true;
            return;
        }
        this.primarySection = storage.section4(chunkPos, y, flags());
    }

    public short biomeIdFrom(ResourceKey<Biome> resourceKey) {
        return previewData.biome2Id().getShort(resourceKey.identifier().toString());
    }

    /**
     * Return {@code true} on successful completion
     */
    protected abstract List<WorkResult> doWork();

    public abstract long flags();

    public boolean isCompleted() {
        return primarySection == null || primarySection.isCompleted(chunkPos);
    }

    public void markCompleted() {
        if (primarySection != null) {
            primarySection.markCompleted(chunkPos);
        }
    }

    public List<WorkResult> work() {
        if (isCanceled || primarySection == null) {
            return List.of();
        }
        try {
            return doWork();
        } catch (Throwable e) {
            LOGGER.error("Unhandled error while processing preview work unit", e);
            throw e;
        }
    }

    public ChunkPos chunk() {
        return chunkPos;
    }

    public int y() {
        return y;
    }

    public void cancel() {
        isCanceled = true;
    }

    public boolean isCanceled() {
        return isCanceled;
    }
}
