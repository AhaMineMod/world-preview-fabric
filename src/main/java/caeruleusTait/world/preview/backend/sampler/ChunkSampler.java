package caeruleusTait.world.preview.backend.sampler;

import caeruleusTait.world.preview.backend.worker.WorkResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public interface ChunkSampler {
    void forEachBlock(ChunkPos chunkPos, int y, BlockPos.MutableBlockPos cursor, BlockConsumer consumer);

    void expandRaw(BlockPos pos, short raw, WorkResult result);

    int blockStride();

    @FunctionalInterface
    interface BlockConsumer {
        void accept(BlockPos pos);
    }
}
