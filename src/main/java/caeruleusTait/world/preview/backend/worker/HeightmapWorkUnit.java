package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.backend.sampler.ChunkSampler;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.mixin.NoiseChunkAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;

import java.util.List;
import java.util.function.Predicate;

public class HeightmapWorkUnit extends WorkUnit {
    private final ChunkSampler sampler;
    private final int numChunks;

    public HeightmapWorkUnit(ChunkSampler sampler, PreviewWorkContext context, ChunkPos chunkPos, int numChunks) {
        super(context, chunkPos, 0);
        this.sampler = sampler;
        this.numChunks = numChunks;
    }

    @Override
    protected List<WorkResult> doWork() {
        final WorkResult res = new WorkResult(this, QuartPos.fromBlock(0), primarySection, new WorkResult.BlockResults(numChunks * numChunks * 4 * 4), List.of());
        final NoiseGeneratorSettings noiseGeneratorSettings = sampleUtils.noiseGeneratorSettings();

        if (noiseGeneratorSettings == null) {
            return List.of(res);
        }

        final NoiseSettings noiseSettings = noiseGeneratorSettings.noiseSettings();
        final NoiseChunk noiseChunk = sampleUtils.getNoiseChunk(chunkPos, numChunks, false);
        final BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();

        final int cellWidth = noiseSettings.getCellWidth();
        final int cellHeight = noiseSettings.getCellHeight();

        final int minY = config.onlySampleInVisualRange ? config.heightmapMinY : noiseSettings.minY();
        final int maxY = config.onlySampleInVisualRange ? config.heightmapMaxY : minY + noiseSettings.height();
        final int cellMinY = Mth.floorDiv(minY, noiseSettings.getCellHeight());
        final int cellCountY = Mth.floorDiv(maxY - minY, noiseSettings.getCellHeight());
        final int cellOffsetY = config.onlySampleInVisualRange ? cellMinY -  Mth.floorDiv(noiseSettings.minY(), noiseSettings.getCellHeight()): 0;

        final int minBlockX = chunkPos.getMinBlockX();
        final int minBlockZ = chunkPos.getMinBlockZ();
        final int cellCountXZ = (16 * numChunks) / cellWidth;
        final int cellStrideXZ = Math.max(1, sampler.blockStride() / cellWidth);
        final int todoArraySize = Math.max(1, cellWidth / sampler.blockStride()) * Math.max(1, cellWidth / sampler.blockStride());
        final int[] posX = new int[todoArraySize];
        final int[] posZ = new int[todoArraySize];
        final double[] posDX = new double[todoArraySize];
        final double[] posDZ = new double[todoArraySize];

        final Predicate<BlockState> predicate = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();

        noiseChunk.initializeForFirstCellX();

        try {
            // Iterate over cell X Z Y
            for(int cellX = 0; cellX < cellCountXZ && !isCanceled(); cellX += cellStrideXZ) {
                noiseChunk.advanceCellX(cellX);

                for(int cellZ = 0; cellZ < cellCountXZ && !isCanceled(); cellZ += cellStrideXZ) {

                    int activePositions = 0;
                    for (int xInCell = 0; xInCell < cellWidth; xInCell += sampler.blockStride()) {
                        for (int zInCell = 0; zInCell < cellWidth; zInCell += sampler.blockStride()) {
                            posX[activePositions] = minBlockX + cellX * cellWidth + xInCell;
                            posZ[activePositions] = minBlockZ + cellZ * cellWidth + zInCell;
                            posDX[activePositions] = (double) xInCell / (double) cellWidth;
                            posDZ[activePositions] = (double) zInCell / (double) cellWidth;
                            activePositions++;
                        }
                    }

                    for(int cellY = cellCountY - 1; cellY >= 0 && activePositions > 0 && !isCanceled(); --cellY) {
                        noiseChunk.selectCellYZ(cellY + cellOffsetY, cellZ);

                        // Iterate over block in cell Y X Z
                        for (int yInCell = cellHeight - 1; yInCell >= 0 && activePositions > 0; --yInCell) {
                            final int y = (cellMinY + cellY) * cellHeight + yInCell;
                            noiseChunk.updateForY(y, (double) yInCell / (double) cellHeight);

                            for (int idx = 0; idx < activePositions; ++idx) {
                                noiseChunk.updateForX(posX[idx], posDX[idx]);
                                noiseChunk.updateForZ(posZ[idx], posDZ[idx]);

                                BlockState blockState = ((NoiseChunkAccessor) noiseChunk).invokeGetInterpolatedState();
                                if (blockState == null) {
                                    blockState = noiseGeneratorSettings.defaultBlock();
                                }

                                if (predicate.test(blockState)) {
                                    mutableBlockPos.set(posX[idx], 0, posZ[idx]);
                                    sampler.expandRaw(mutableBlockPos, (short) (y + 1), res);
                                    activePositions--;
                                    if (idx < activePositions) {
                                        posX[idx] = posX[activePositions];
                                        posZ[idx] = posZ[activePositions];
                                        posDX[idx] = posDX[activePositions];
                                        posDZ[idx] = posDZ[activePositions];
                                    }
                                    idx--;
                                }
                            }
                        }
                    }
                }

                // Whatever this does, but it is required...
                noiseChunk.swapSlices();
            }
        } finally {
            noiseChunk.stopInterpolation();
        }

        return List.of(res);
    }

    @Override
    public long flags() {
        return PreviewStorage.FLAG_HEIGHT;
    }
}
