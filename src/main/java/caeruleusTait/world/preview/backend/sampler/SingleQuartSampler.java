package caeruleusTait.world.preview.backend.sampler;

import caeruleusTait.world.preview.backend.worker.WorkResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

public class SingleQuartSampler implements ChunkSampler {
    @Override
    public void forEachBlock(ChunkPos chunkPos, int y, BlockPos.MutableBlockPos cursor, BlockConsumer consumer) {
        final int xMin = SectionPos.sectionToBlockCoord(chunkPos.x, 0);
        final int zMin = SectionPos.sectionToBlockCoord(chunkPos.z, 0);
        cursor.set(xMin, y, zMin);
        consumer.accept(cursor);
    }

    @Override
    public void expandRaw(BlockPos pos, short raw, WorkResult result) {
        final int quartX = QuartPos.fromBlock(pos.getX());
        final int quartZ = QuartPos.fromBlock(pos.getZ());

        for (int x = 0; x < 16 / QuartPos.SIZE; x++) {
            for (int z = 0; z < 16 / QuartPos.SIZE; z++) {
                result.results().add(quartX + x, quartZ + z, raw);
            }
        }
    }

    @Override
    public int blockStride() {
        return 16;
    }
}
