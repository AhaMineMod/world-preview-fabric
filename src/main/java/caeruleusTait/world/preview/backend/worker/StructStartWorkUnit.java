package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.List;

public class StructStartWorkUnit extends WorkUnit {
    public StructStartWorkUnit(PreviewWorkContext context, ChunkPos pos) {
        super(context, pos, 0);
    }

    @Override
    protected List<WorkResult> doWork() {
        List<Pair<Identifier, StructureStart>> res = sampleUtils.doStructures(chunkPos);
        return List.of(
                new WorkResult(
                        this,
                        0,
                        primarySection,
                        new WorkResult.BlockResults(0),
                        res
                )
        );
    }

    @Override
    public long flags() {
        return PreviewStorage.FLAG_STRUCT_START;
    }
}
