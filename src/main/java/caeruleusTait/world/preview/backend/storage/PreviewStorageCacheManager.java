package caeruleusTait.world.preview.backend.storage;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public interface PreviewStorageCacheManager {

    int CACHE_FORMAT_VERSION = 1;
    ExecutorService CACHE_WRITE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "world-preview-cache-writer");
        thread.setDaemon(true);
        return thread;
    });

    PreviewStorage loadPreviewStorage(long seed, int yMin, int yMax);

    void storePreviewStorage(long seed, PreviewStorage storage);

    Path cacheDir();

    default String cacheFileCompatPart() {
        final WorldPreview worldPreview = WorldPreview.get();
        final RenderSettings settings = worldPreview.renderSettings();
        final WorldPreviewConfig cfg = worldPreview.cfg();

        long flags = 0;
        flags |= CACHE_FORMAT_VERSION & 0b1111;
        flags |= (settings.samplerType.ordinal() & 0b1111) << 4;
        flags |= (PreviewSection.SHIFT & 0b1111) << 8;
        flags |= (PreviewBlock.PREVIEW_BLOCK_SHIFT & 0b1111) << 12;
        flags |= cfg.enableCompression ? 1 << 16 : 0;

        return String.format("%s-%d-%d", settings.dimension, settings.pixelsPerChunk(), flags)
                .replace(":", "_")
                .replace(";", "_")
                .replace("/", "_")
                .replace("\\", "_");
    }

    default void clearCache() {
        try (var stream = Files.walk(cacheDir())) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw new RuntimeException(e.getCause());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    default void writeCacheFile(PreviewStorage storage, Path outFile) {
        Path outFileTmp = outFile.getParent().resolve(outFile.getFileName().toString() + ".tmp");
        WorldPreview.LOGGER.info("Writing preview data to {}", outFile);

        ZipEntry entry = new ZipEntry("bin");
        try (
                FileOutputStream fos = new FileOutputStream(outFileTmp.toFile());
                ZipOutputStream zos = new ZipOutputStream(fos)
        ) {
            zos.putNextEntry(entry);
            ObjectOutputStream oos = new ObjectOutputStream(zos);
            oos.writeObject(storage);
            zos.closeEntry();
        } catch (IOException e) {
            WorldPreview.LOGGER.error("Failed to write cached preview data to {}", outFile, e);
        }

        try {
            Files.move(outFileTmp, outFile, REPLACE_EXISTING);
        } catch (IOException e) {
            WorldPreview.LOGGER.error("Failed to move cached preview data to {} --> {}", outFileTmp, outFile, e);
        }
    }

    default void storePreviewStorageAsync(long seed, PreviewStorage storage) {
        if (storage == null) {
            return;
        }
        CACHE_WRITE_EXECUTOR.execute(() -> {
            try {
                storePreviewStorage(seed, storage);
            } catch (Throwable t) {
                WorldPreview.LOGGER.error("Failed to asynchronously store preview cache", t);
            }
        });
    }

    default PreviewStorage readCacheFile(int yMin, int yMax, Path inFile) {
        if (!Files.exists(inFile)) {
            return new PreviewStorage(yMin, yMax);
        }

        WorldPreview.LOGGER.info("Reading preview data from {}", inFile);

        try (
                FileInputStream fis = new FileInputStream(inFile.toFile());
                ZipInputStream zis = new ZipInputStream(fis)
        ) {
            zis.getNextEntry();
            ObjectInputStream ois = new ObjectInputStream(zis);
            return (PreviewStorage) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            WorldPreview.LOGGER.error("Failed to read cached preview data from {}", inFile, e);
            return new PreviewStorage(yMin, yMax);
        }
    }

}
