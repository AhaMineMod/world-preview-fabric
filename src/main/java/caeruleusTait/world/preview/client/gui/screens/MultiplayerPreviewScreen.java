package caeruleusTait.world.preview.client.gui.screens;

import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.client.WorldPreviewComponents;
import caeruleusTait.world.preview.client.gui.PreviewContainerDataProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.commands.Commands;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Mth;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static caeruleusTait.world.preview.client.WorldPreviewComponents.MSG_SEED_REQUEST_FAILED;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.MSG_SEED_REQUEST_SENT;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.SEED_LABEL_MULTIPLAYER;

public class MultiplayerPreviewScreen extends Screen implements PreviewContainerDataProvider {

    private static final long SEED_REQUEST_TIMEOUT_MS = 7_000L;
    private static final Pattern COMMAND_SEED_PATTERN = Pattern.compile("(?<![-\\d])(-?\\d{1,19})(?!\\d)");
    private static final Pattern TEXT_SEED_PATTERN = Pattern.compile("(?i)(?:seed|种子|semente|ключ)\\s*[:：]\\s*(-?\\d{1,19})");
    private static @Nullable MultiplayerPreviewScreen activeScreen;
    private static boolean pendingSeedRequest = false;
    private static long seedRequestDeadline = 0L;

    private final WorldPreview worldPreview = WorldPreview.get();
    private final ExecutorService loadingExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "world-preview-multiplayer-loader");
        thread.setDaemon(true);
        return thread;
    });

    private PreviewContainer previewContainer;
    private boolean pendingContainerStart = false;
    private String seed;
    private @Nullable WorldCreationContext cachedContext;
    private @Nullable String cachedContextSeed;

    public MultiplayerPreviewScreen() {
        super(WorldPreviewComponents.TITLE_FULL);
        seed = "";
    }

    @Override
    protected void init() {
        if (previewContainer == null) {
            seed = storedSeed();
            previewContainer = new PreviewContainer(this, this);
            pendingContainerStart = true;
        }
        activeScreen = this;

        previewContainer.widgets().forEach(this::addRenderableWidget);
        previewContainer.doLayout(new ScreenRectangle(0, 18, width, height - 38));

        Button btn = Button
                .builder(CommonComponents.GUI_BACK, x -> onClose())
                .width(100)
                .pos(width / 2 - 50, height - 24)
                .build();
        addRenderableWidget(btn);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(minecraft.font, WorldPreviewComponents.TITLE_FULL, width / 2, 6, 0xFFFFFF);
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, FOOTER_SEPARATOR, 0, Mth.roundToward(this.height - 30, 2), 0.0F, 0.0F, this.width, 2, 32, 2);
    }

    @Override
    public void tick() {
        super.tick();
        if (pendingContainerStart && previewContainer != null) {
            pendingContainerStart = false;
            previewContainer.start();
        }

        if (pendingSeedRequest && System.currentTimeMillis() > seedRequestDeadline) {
            clearPendingSeedRequest();
            showClientMessage(MSG_SEED_REQUEST_FAILED);
        }
    }

    @Override
    public void onClose() {
        pendingContainerStart = false;
        clearPendingSeedRequest();
        if (activeScreen == this) {
            activeScreen = null;
        }
        worldPreview.saveConfig();
        loadingExecutor.shutdownNow();
        if (previewContainer != null) {
            previewContainer.close();
        }
        super.onClose();
    }

    @Override
    public @Nullable WorldCreationContext previewWorldCreationContext() {
        worldPreview.ensureBasePreviewMappingsLoaded();
        if (seed.equals(cachedContextSeed) && cachedContext != null) {
            return cachedContext;
        }

        WorldDataConfiguration worldDataConfiguration = WorldDataConfiguration.DEFAULT;
        PackRepository packRepository = minecraft.getResourcePackRepository();
        WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(packRepository, worldDataConfiguration, false, true);
        WorldLoader.InitConfig initConfig = new WorldLoader.InitConfig(
                packConfig,
                Commands.CommandSelection.INTEGRATED,
                LevelBasedPermissionSet.ADMIN
        );

        CompletableFuture<WorldCreationContext> completableFuture = WorldLoader.load(
                initConfig,
                dataLoadContext -> {
                    WorldDimensions worldDimensions = WorldPresets.createNormalWorldDimensions(dataLoadContext.datapackWorldgen());
                    WorldGenSettings worldGenSettings = new WorldGenSettings(worldOptions(null), worldDimensions);
                    return new WorldLoader.DataLoadOutput<>(
                            worldGenSettings,
                            dataLoadContext.datapackDimensions()
                    );
                },
                (closeableResourceManager, reloadableServerResources, layeredRegistryAccess, worldGenSettings) -> {
                    closeableResourceManager.close();
                    return new WorldCreationContext(worldGenSettings, layeredRegistryAccess, reloadableServerResources, worldDataConfiguration);
                },
                loadingExecutor,
                loadingExecutor
        );

        try {
            cachedContext = completableFuture.get();
            cachedContextSeed = seed;
            worldPreview.ensureBasePreviewMappingsLoaded();
            return cachedContext;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Path cacheDir() {
        final Path previewDir = worldPreview.configDir()
                .resolve("world-preview-multiplayer")
                .resolve(sanitize(serverIdentity(minecraft)));
        try {
            Files.createDirectories(previewDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create multiplayer preview cache directory", e);
        }
        return previewDir;
    }

    private String filename(long seed) {
        return String.format("%s-%s.zip", seed, cacheFileCompatPart());
    }

    @Override
    public void storePreviewStorage(long seed, PreviewStorage storage) {
        if (!worldPreview.cfg().cacheInGame) {
            return;
        }
        writeCacheFile(storage, cacheDir().resolve(filename(seed)));
    }

    @Override
    public PreviewStorage loadPreviewStorage(long seed, int yMin, int yMax) {
        if (!worldPreview.cfg().cacheInGame) {
            return new PreviewStorage(yMin, yMax);
        }

        return readCacheFile(yMin, yMax, cacheDir().resolve(filename(seed)));
    }

    @Override
    public void registerSettingsChangeListener(Runnable listener) {
        // Multiplayer preview has no world creation UI state to observe.
    }

    @Override
    public String seed() {
        return seed;
    }

    @Override
    public void updateSeed(String newSeed) {
        seed = newSeed;
        cachedContext = null;
        cachedContextSeed = null;
        Map<String, String> multiplayerSeeds = multiplayerSeeds(worldPreview);
        if (newSeed.isBlank()) {
            multiplayerSeeds.remove(serverIdentity(minecraft));
        } else {
            multiplayerSeeds.put(serverIdentity(minecraft), newSeed);
        }
    }

    @Override
    public boolean seedIsEditable() {
        return true;
    }

    @Override
    public boolean randomizeSeedWhenEmpty() {
        return false;
    }

    @Override
    public boolean seedRequestAvailable() {
        return true;
    }

    @Override
    public void requestSeed() {
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) {
            showClientMessage(MSG_SEED_REQUEST_FAILED);
            return;
        }

        markSeedRequestSent();
        connection.sendCommand("seed");
        showClientMessage(MSG_SEED_REQUEST_SENT);
    }

    @Override
    public Component seedTooltip() {
        return SEED_LABEL_MULTIPLAYER;
    }

    public static void markSentCommand(String command) {
        if (command.trim().equalsIgnoreCase("seed")) {
            markSeedRequestSent();
        }
    }

    public static void handleSeedResponse(Component message) {
        if (!pendingSeedRequest) {
            return;
        }

        OptionalLong parsedSeed = parseSeedResponse(message);
        if (parsedSeed.isEmpty()) {
            if (isSeedRequestFailure(message)) {
                clearPendingSeedRequest();
                showClientMessage(Minecraft.getInstance(), MSG_SEED_REQUEST_FAILED);
            }
            return;
        }

        clearPendingSeedRequest();
        String seedText = String.valueOf(parsedSeed.getAsLong());
        storeSeed(Minecraft.getInstance(), seedText);

        if (activeScreen == null || activeScreen.previewContainer == null) {
            showClientMessage(Minecraft.getInstance(), Component.translatable("world_preview.preview.seed-request.success", seedText));
        } else {
            activeScreen.previewContainer.setSeed(seedText);
            activeScreen.showClientMessage(Component.translatable("world_preview.preview.seed-request.success", seedText));
        }
    }

    private static OptionalLong parseSeedResponse(Component message) {
        String rendered = message.getString();
        String debug = message.toString();
        if (debug.contains("commands.seed.success")) {
            return firstLong(COMMAND_SEED_PATTERN.matcher(debug + " " + rendered));
        }

        Matcher textMatcher = TEXT_SEED_PATTERN.matcher(rendered);
        if (textMatcher.find()) {
            return parseLong(textMatcher.group(1));
        }

        String renderedLower = rendered.toLowerCase(Locale.ROOT);
        if (renderedLower.equals("seed") || !renderedLower.contains("seed")) {
            return OptionalLong.empty();
        }

        return firstLong(COMMAND_SEED_PATTERN.matcher(rendered));
    }

    private static OptionalLong firstLong(Matcher matcher) {
        while (matcher.find()) {
            OptionalLong parsed = parseLong(matcher.group(1));
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return OptionalLong.empty();
    }

    private static OptionalLong parseLong(String rawValue) {
        try {
            return OptionalLong.of(Long.parseLong(rawValue));
        } catch (NumberFormatException ignored) {
            return OptionalLong.empty();
        }
    }

    private static boolean isSeedRequestFailure(Component message) {
        String renderedLower = message.getString().toLowerCase(Locale.ROOT);
        String debug = message.toString();
        return debug.contains("commands.generic.permission")
                || debug.contains("dispatcherUnknownCommand")
                || debug.contains("dispatcherUnknownArgument")
                || renderedLower.contains("permission")
                || renderedLower.contains("not allowed")
                || renderedLower.contains("unknown")
                || renderedLower.contains("incomplete")
                || renderedLower.contains("权限")
                || renderedLower.contains("未知");
    }

    private static void markSeedRequestSent() {
        pendingSeedRequest = true;
        seedRequestDeadline = System.currentTimeMillis() + SEED_REQUEST_TIMEOUT_MS;
    }

    private static void clearPendingSeedRequest() {
        pendingSeedRequest = false;
        seedRequestDeadline = 0L;
    }

    @Override
    public @Nullable Path tempDataPackDir() {
        return null;
    }

    @Override
    public @Nullable MinecraftServer minecraftServer() {
        return null;
    }

    @Override
    public WorldOptions worldOptions(@Nullable WorldCreationContext wcContext) {
        long parsedSeed = WorldOptions.parseSeed(seed).orElse(0L);
        return new WorldOptions(parsedSeed, true, false);
    }

    @Override
    public WorldDataConfiguration worldDataConfiguration(@Nullable WorldCreationContext wcContext) {
        return WorldDataConfiguration.DEFAULT;
    }

    @Override
    public RegistryAccess.Frozen registryAccess(@Nullable WorldCreationContext wcContext) {
        if (wcContext == null) throw new AssertionError();
        return wcContext.worldgenLoadContext();
    }

    @Override
    public Registry<LevelStem> levelStemRegistry(@Nullable WorldCreationContext wcContext) {
        if (wcContext == null) throw new AssertionError();
        WorldDimensions.Complete worldDimensions = wcContext.selectedDimensions().bake(wcContext.datapackDimensions());
        return worldDimensions.dimensions();
    }

    @Override
    public LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess(@Nullable WorldCreationContext wcContext) {
        if (wcContext == null) throw new AssertionError();
        WorldDimensions.Complete worldDimensions = wcContext.selectedDimensions().bake(wcContext.datapackDimensions());
        return wcContext
                .worldgenRegistries()
                .replaceFrom(RegistryLayer.DIMENSIONS, worldDimensions.dimensionsRegistryAccess());
    }

    private String storedSeed() {
        return multiplayerSeeds(worldPreview).getOrDefault(serverIdentity(minecraft), "");
    }

    private static void storeSeed(Minecraft minecraft, String seed) {
        WorldPreview worldPreview = WorldPreview.get();
        multiplayerSeeds(worldPreview).put(serverIdentity(minecraft), seed);
        worldPreview.saveConfig();
    }

    private static Map<String, String> multiplayerSeeds(WorldPreview worldPreview) {
        if (worldPreview.cfg().multiplayerSeeds == null) {
            worldPreview.cfg().multiplayerSeeds = new HashMap<>();
        }
        return worldPreview.cfg().multiplayerSeeds;
    }

    private static String serverIdentity(Minecraft minecraft) {
        ServerData serverData = minecraft.getCurrentServer();
        if (serverData != null) {
            String ip = serverData.ip == null ? "" : serverData.ip;
            String name = serverData.name == null ? "" : serverData.name;
            String identity = (ip + "-" + name).trim();
            if (!identity.isBlank()) {
                return identity;
            }
        }
        return "unknown-multiplayer-server";
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void showClientMessage(Component component) {
        showClientMessage(minecraft, component);
    }

    private static void showClientMessage(Minecraft minecraft, Component component) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(component, false);
        }
    }
}
