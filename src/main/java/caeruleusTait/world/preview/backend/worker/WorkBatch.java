package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.backend.storage.PreviewSection;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static caeruleusTait.world.preview.WorldPreview.LOGGER;

public class WorkBatch {
    public final List<WorkUnit> workUnits;
    private final Object completedSynchro;
    private final BooleanSupplier shouldApplyResults;
    private volatile boolean isCanceled = false;
    private volatile boolean isDone = false;

    public WorkBatch(List<WorkUnit> workUnits, Object completedSynchro, BooleanSupplier shouldApplyResults) {
        this.workUnits = workUnits;
        this.completedSynchro = completedSynchro;
        this.shouldApplyResults = shouldApplyResults;
    }

    public boolean isCanceled() {
        return isCanceled;
    }

    public boolean isDone() {
        return isDone;
    }

    public void cancel() {
        isCanceled = true;
        workUnits.forEach(WorkUnit::cancel);
    }

    public void process() {
        try {
            if (isCanceled()) {
                return;
            }

            List<WorkResult> res = new ArrayList<>();
            for (WorkUnit unit : workUnits) {
                res.addAll(unit.work());
                if (isCanceled()) {
                    return;
                }
            }

            if (isCanceled() || !shouldApplyResults.getAsBoolean()) {
                return;
            }

            // Mark as completed early to avoid duplicate work
            synchronized (completedSynchro) {
                if (isCanceled() || !shouldApplyResults.getAsBoolean()) {
                    return;
                }
                for (WorkUnit unit : workUnits) {
                    unit.markCompleted();
                    unit.context.onWorkUnitCompleted(unit.flags());
                }
            }

            if (isCanceled() || !shouldApplyResults.getAsBoolean()) {
                return;
            }
            applyChunkResult(res);
        } catch (Exception e) {
            LOGGER.error("Unhandled error while processing preview work batch", e);
        } finally {
            isDone = true;
        }
    }

    private void applyChunkResult(List<WorkResult> workResultList) {
        try {
            for (WorkResult workResult : workResultList) {
                if (workResult == null) {
                    return;
                }

                final ChunkPos chunkPos = workResult.workUnit().chunk();
                final int qStartX = QuartPos.fromSection(chunkPos.x);
                final int qStartZ = QuartPos.fromSection(chunkPos.z);

                PreviewSection section = workResult.section();
                PreviewSection.AccessData offsetData = section.calcQuartOffsetData(qStartX, qStartZ, qStartX + 4, qStartZ + 4);

                // Assume that one chunk always fits
                WorkResult.BlockResults results = workResult.results();
                for (int i = 0; i < results.size(); ++i) {
                    section.set(
                            offsetData.minX() + results.quartX(i) - qStartX,
                            offsetData.minZ() + results.quartZ(i) - qStartZ,
                            results.value(i)
                    );
                }

                for (Pair<Identifier, StructureStart> x : workResult.structures()) {
                    StructureStart structureStart = x.getSecond();
                    short id = workResult.workUnit().previewData.struct2Id().getShort(x.getFirst().toString());
                    section.addStructure(new PreviewSection.PreviewStruct(
                            structureStart.getBoundingBox().getCenter(),
                            id,
                            structureStart.getBoundingBox()
                    ));
                }
            }
        } catch (Throwable e) {
            LOGGER.error("Unhandled error while applying preview work result", e);
        }
    }
}
