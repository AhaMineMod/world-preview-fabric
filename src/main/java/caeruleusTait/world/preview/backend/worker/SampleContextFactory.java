package caeruleusTait.world.preview.backend.worker;

import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.Proxy;
import java.nio.file.Path;

public final class SampleContextFactory {
    private SampleContextFactory() {
    }

    public static SampleUtils createForServer(
            @NotNull MinecraftServer server,
            BiomeSource biomeSource,
            ChunkGenerator chunkGenerator,
            WorldOptions worldOptions,
            LevelStem levelStem,
            LevelHeightAccessor levelHeightAccessor
    ) {
        return new SampleUtils(server, biomeSource, chunkGenerator, worldOptions, levelStem, levelHeightAccessor);
    }

    public static SampleUtils createForPreview(
            BiomeSource biomeSource,
            ChunkGenerator chunkGenerator,
            LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess,
            WorldOptions worldOptions,
            LevelStem levelStem,
            LevelHeightAccessor levelHeightAccessor,
            WorldDataConfiguration worldDataConfiguration,
            Proxy proxy,
            @Nullable Path tempDataPackDir
    ) throws IOException {
        return new SampleUtils(
                biomeSource,
                chunkGenerator,
                layeredRegistryAccess,
                worldOptions,
                levelStem,
                levelHeightAccessor,
                worldDataConfiguration,
                proxy,
                tempDataPackDir
        );
    }
}
