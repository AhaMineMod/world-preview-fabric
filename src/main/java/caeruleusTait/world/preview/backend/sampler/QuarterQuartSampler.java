package caeruleusTait.world.preview.backend.sampler;

import caeruleusTait.world.preview.backend.worker.WorkResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

public class QuarterQuartSampler implements ChunkSampler {
    @Override
    public void forEachBlock(ChunkPos chunkPos, int y, BlockPos.MutableBlockPos cursor, BlockConsumer consumer) {
        final int xMin = SectionPos.sectionToBlockCoord(chunkPos.x, 0);
        final int zMin = SectionPos.sectionToBlockCoord(chunkPos.z, 0);

        for (int x = 0; x < 16; x += QuartPos.SIZE * 2) {
            for (int z = 0; z < 16; z += QuartPos.SIZE * 2) {
                cursor.set(xMin + x, y, zMin + z);
                consumer.accept(cursor);
            }
        }
    }

    @Override
    public void expandRaw(BlockPos pos, short raw, WorkResult result) {
        final int quartX = QuartPos.fromBlock(pos.getX());
        final int quartZ = QuartPos.fromBlock(pos.getZ());
        result.results().add(quartX, quartZ, raw);
        result.results().add(quartX, quartZ + 1, raw);
        result.results().add(quartX + 1, quartZ, raw);
        result.results().add(quartX + 1, quartZ + 1, raw);
    }

    @Override
    public int blockStride() {
        return QuartPos.SIZE * 2;
    }
}
