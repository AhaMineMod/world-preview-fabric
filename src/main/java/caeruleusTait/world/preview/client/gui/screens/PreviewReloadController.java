package caeruleusTait.world.preview.client.gui.screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class PreviewReloadController implements AutoCloseable {
    private final ExecutorService reloadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "world-preview-reload");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger revision = new AtomicInteger(0);

    public void reload(
            Minecraft minecraft,
            Supplier<@Nullable WorldCreationContext> contextSupplier,
            Consumer<@Nullable WorldCreationContext> applyContext,
            Consumer<@Nullable Throwable> onLatestComplete
    ) {
        final int currentRevision = revision.incrementAndGet();
        CompletableFuture
                .supplyAsync(() -> {
                    if (isStale(currentRevision)) {
                        return null;
                    }
                    return contextSupplier.get();
                }, reloadExecutor)
                .thenAcceptAsync(context -> {
                    if (!isStale(currentRevision)) {
                        applyContext.accept(context);
                    }
                }, minecraft)
                .handle((result, error) -> {
                    if (!isStale(currentRevision)) {
                        onLatestComplete.accept(error);
                    }
                    return null;
                });
    }

    private boolean isStale(int currentRevision) {
        return revision.get() > currentRevision;
    }

    @Override
    public void close() {
        revision.incrementAndGet();
        reloadExecutor.shutdownNow();
    }
}
