package caeruleusTait.world.preview.client.gui;

import caeruleusTait.world.preview.backend.storage.PreviewStorageCacheManager;
import caeruleusTait.world.preview.client.WorldPreviewComponents;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

public interface PreviewContainerDataProvider extends PreviewStorageCacheManager {

    @Nullable WorldCreationContext previewWorldCreationContext();

    void registerSettingsChangeListener(Runnable listener);

    String seed();

    void updateSeed(String newSeed);

    boolean seedIsEditable();

    default boolean randomizeSeedWhenEmpty() {
        return seedIsEditable();
    }

    default boolean seedRequestAvailable() {
        return false;
    }

    default void requestSeed() {
    }

    default Component seedTooltip() {
        return WorldPreviewComponents.SEED_LABEL;
    }

    @Nullable Path tempDataPackDir();

    @Nullable MinecraftServer minecraftServer();

    WorldOptions worldOptions(@Nullable WorldCreationContext wcContext);

    WorldDataConfiguration worldDataConfiguration(@Nullable WorldCreationContext wcContext);

    RegistryAccess.Frozen registryAccess(@Nullable WorldCreationContext wcContext);

    Registry<LevelStem> levelStemRegistry(@Nullable WorldCreationContext wcContext);

    LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess(@Nullable WorldCreationContext wcContext);
}
