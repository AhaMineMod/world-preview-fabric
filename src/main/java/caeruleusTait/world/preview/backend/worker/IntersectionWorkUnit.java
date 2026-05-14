package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.backend.sampler.ChunkSampler;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.mixin.NoiseChunkAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;

import java.util.ArrayList;
import java.util.List;

public class IntersectionWorkUnit extends WorkUnit {
    private final ChunkSampler sampler;
    private final int numChunks;
    private final int yStride;

    public IntersectionWorkUnit(
            ChunkSampler sampler,
            PreviewWorkContext context,
            ChunkPos chunkPos,
            int numChunks,
            int yStride
    ) {
        super(context, chunkPos, 0);
        this.sampler = sampler;
        this.numChunks = numChunks;
        this.yStride = yStride;
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    protected List<WorkResult> doWork() {
        final NoiseGeneratorSettings noiseGeneratorSettings = sampleUtils.noiseGeneratorSettings();

        if (noiseGeneratorSettings == null) {
            return List.of();
        }

        final NoiseSettings noiseSettings = noiseGeneratorSettings.noiseSettings();
        final NoiseChunk noiseChunk = sampleUtils.getNoiseChunk(chunkPos, numChunks, true);
        final BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();

        final int yMin = noiseSettings.minY();
        final int yMax = yMin + noiseSettings.height();
        final int cellWidth = noiseSettings.getCellWidth();
        final int cellHeight = noiseSettings.getCellHeight();

        final int cellCountY = Math.floorDiv(noiseSettings.height(), noiseSettings.getCellHeight());

        final int minBlockX = chunkPos.getMinBlockX();
        final int minBlockZ = chunkPos.getMinBlockZ();
        final int cellCountXZ = (16 * numChunks) / cellWidth;
        final int cellStrideXZ = Math.max(1, sampler.blockStride() / cellWidth);
        final int todoArraySize = Math.max(1, cellWidth / sampler.blockStride()) * Math.max(1, cellWidth / sampler.blockStride());
        final int[] posX = new int[todoArraySize];
        final int[] posZ = new int[todoArraySize];
        final double[] posDX = new double[todoArraySize];
        final double[] posDZ = new double[todoArraySize];
        final short[] lastValues = new short[todoArraySize];

        final List<WorkResult> results = new ArrayList<>((yMax - yMin) / yStride);

        // Initialize the results for each y-level
        for (int y = yMin; y <= yMax; y += yStride) {
            results.add(
                    new WorkResult(
                            this,
                            QuartPos.fromBlock(y),
                            y == this.y ? primarySection : storage.section4(chunkPos, y, flags()),
                            new WorkResult.BlockResults(numChunks * numChunks * 4 * 4),
                            List.of()
                    )
            );
        }

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
                            lastValues[activePositions] = 0;
                            activePositions++;
                        }
                    }

                    int lastCellY = Integer.MIN_VALUE;
                    for (int yTemp = yMin; yTemp <= yMax; yTemp += yStride) {
                        final int y = Math.min(yTemp, yMax - 1);
                        final int cellY = Math.min(Math.floorDiv(y - yMin, cellHeight), cellCountY - 1);
                        final int yInCell = y % cellHeight;
                        if (cellY != lastCellY) {
                            noiseChunk.selectCellYZ(cellY, cellZ);
                        }
                        noiseChunk.updateForY(y, (double) yInCell / (double) cellHeight);
                        lastCellY = cellY;

                        final WorkResult res = results.get((yTemp - yMin) / yStride);
                        for (int idx = 0; idx < activePositions; ++idx) {
                            noiseChunk.updateForX(posX[idx], posDX[idx]);
                            noiseChunk.updateForZ(posZ[idx], posDZ[idx]);

                            BlockState blockState = ((NoiseChunkAccessor) noiseChunk).invokeGetInterpolatedState();
                            if (blockState == null) {
                                blockState = noiseGeneratorSettings.defaultBlock();
                            }

                            short colorId = (short) blockState.getMapColor(null, null).id;
                            short lastId = lastValues[idx];
                            lastValues[idx] = colorId;

                            // Allow "seeing through" one layer of air
                            if (colorId == 0 && lastId > 0) {
                                colorId = (short) -lastId;
                            }

                            mutableBlockPos.set(posX[idx], yTemp, posZ[idx]);
                            sampler.expandRaw(mutableBlockPos, colorId, res);
                        }
                    }
                }

                // Whatever this does, but it is required...
                noiseChunk.swapSlices();
            }
        } finally {
            noiseChunk.stopInterpolation();
        }

        return results;
    }

    @Override
    public long flags() {
        return PreviewStorage.FLAG_INTERSECT;
    }
}
