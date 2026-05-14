package caeruleusTait.world.preview.backend.worker;

import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;

import java.util.function.LongConsumer;

public record PreviewWorkContext(
        SampleUtils sampleUtils,
        PreviewStorage storage,
        PreviewData previewData,
        WorldPreviewConfig config,
        LongConsumer completionCallback
) {
    public void onWorkUnitCompleted(long flags) {
        completionCallback.accept(flags);
    }
}
