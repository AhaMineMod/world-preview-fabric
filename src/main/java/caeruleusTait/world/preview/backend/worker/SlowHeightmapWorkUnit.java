package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.backend.sampler.ChunkSampler;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;

import java.util.List;

public class SlowHeightmapWorkUnit extends WorkUnit {
    private final ChunkSampler sampler;

    public SlowHeightmapWorkUnit(ChunkSampler sampler, PreviewWorkContext context, ChunkPos chunkPos) {
        super(context, chunkPos, 0);
        this.sampler = sampler;
    }

    @Override
    protected List<WorkResult> doWork() {
        WorkResult res = new WorkResult(this, QuartPos.fromBlock(0), primarySection, new WorkResult.BlockResults(16), List.of());
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        sampler.forEachBlock(chunkPos, y, cursor, p -> sampler.expandRaw(p, sampleUtils.doHeightSlow(p), res));
        return List.of(res);
    }

    @Override
    public long flags() {
        return PreviewStorage.FLAG_HEIGHT;
    }
}
