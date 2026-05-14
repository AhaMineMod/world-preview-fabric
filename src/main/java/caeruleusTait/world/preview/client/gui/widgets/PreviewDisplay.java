package caeruleusTait.world.preview.client.gui.widgets;

import caeruleusTait.world.preview.RenderSettings;
import caeruleusTait.world.preview.WorldPreview;
import caeruleusTait.world.preview.WorldPreviewConfig;
import caeruleusTait.world.preview.backend.WorkManager;
import caeruleusTait.world.preview.backend.color.PreviewData;
import caeruleusTait.world.preview.backend.storage.PreviewSection;
import caeruleusTait.world.preview.backend.storage.PreviewStorage;
import caeruleusTait.world.preview.client.WorldPreviewClient;
import caeruleusTait.world.preview.client.gui.PreviewDisplayDataProvider;
import caeruleusTait.world.preview.client.gui.widgets.lists.BiomesList;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.shorts.Short2LongMap;
import it.unimi.dsi.fastutil.shorts.Short2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import static caeruleusTait.world.preview.client.WorldPreviewComponents.MSG_ERROR_SETUP_FAILED;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.MSG_PREVIEW_SETUP_LOADING;

public class PreviewDisplay extends AbstractWidget implements AutoCloseable {
    private static final double MIN_ZOOM_FACTOR = 0.5;
    private static final double MAX_ZOOM_FACTOR = 16.0;
    private static final double ZOOM_SCROLL_FACTOR = 1.1;
    private static final int STRUCTURE_ICON_TARGET_SIZE_GUI = 14;
    private static final long SELECTED_STRUCTURE_PULSE_MS = 1800L;
    private static final float SELECTED_STRUCTURE_BOB_PIXELS_GUI = 1.0f;
    private static final long TEXTURE_REFRESH_IDLE_MS = 120L;
    private static final long TEXTURE_REFRESH_MOVING_MS = 33L;
    private static final long GENERATION_QUEUE_REFRESH_MS = 120L;
    private static final long PLAYER_MARKER_REFRESH_MS = 100L;
    private static final long TEXTURE_RENDER_BUDGET_NS = 4_000_000L;
    private static final long TEXTURE_RENDER_BUDGET_DRAGGING_NS = 6_000_000L;
    private static final long TEXTURE_RENDER_BUDGET_DRAG_BURST_NS = 10_000_000L;
    private static final long DRAG_RENDER_BURST_DURATION_MS = 300L;
    private static final double DRAG_CLICK_THRESHOLD_BLOCKS = 4.0;
    private static final long DRAG_PREDICTION_HORIZON_MS = 220L;
    private static final double DRAG_PREDICTION_MAX_VIEW_MULTIPLIER = 0.75;
    private static final double DRAG_VELOCITY_SMOOTHING = 0.35;
    private static final double DRAG_PREDICTION_MIN_SPEED_BLOCKS_PER_SEC = 24.0;
    private static final double MIN_SILENT_ZOOM_FACTOR = 0.0625;
    private static final double MAX_SILENT_ZOOM_FACTOR = 64.0;
    private static final int PROGRESS_BAR_WIDTH = 120;
    private static final int PROGRESS_BAR_HEIGHT = 5;
    private static final RenderSettings.RenderMode[] RENDER_MODES = RenderSettings.RenderMode.values();

    private final Minecraft minecraft;
    private final PreviewDisplayDataProvider dataProvider;
    private final WorkManager workManager;
    private final RenderSettings renderSettings;
    private final WorldPreviewConfig config;
    private Short2LongMap visibleBiomes;
    private Short2LongMap visibleStructures;
    private NativeImage previewImg;
    private NativeImage panScratchImg;
    private DynamicTexture previewTexture;
    private Identifier previewTextureId;
    private long[] workingVisibleBiomes;
    private long[] workingVisibleStructures;
    private int[] colorMap;
    private int[] colorMapGrayScale;
    private int[] heightColorMap;
    private int[] noiseColorMap;
    private boolean[] cavesMap;
    private final PreviewIconTextureCache iconTextureCache;
    private PreviewIconTextureCache.IconData[] structureIcons;
    private PreviewIconTextureCache.IconData playerIcon;
    private PreviewIconTextureCache.IconData spawnIcon;
    private ItemStack[] structureItems;
    private PreviewDisplayDataProvider.StructureRenderInfo[] structureRenderInfoMap;

    private Component coordinatesCopiedMsg = null;
    private long coordinatesCopiedAtMs = 0L;

    private int texWidth = 100;
    private int texHeight = 100;

    private short selectedBiomeId;
    private boolean highlightCaves;

    private double totalDragX = 0;
    private double totalDragZ = 0;
    private double dragVelocityBlocksX = 0.0;
    private double dragVelocityBlocksZ = 0.0;
    private long lastDragSampleMs = 0L;

    private int scaleBlockPos = 1;
    private double zoomFactor = 1.0;
    private double silentZoomFactor = 1.0;
    private boolean silentZoomInitialized = false;

    private StructHoverHelperCell[] hoverHelperGrid;
    private final int hoverHelperGridCellSize = 64;
    private int hoverHelperGridWidth;
    private int hoverHelperGridHeight;
    private boolean hoverHelperGridDirty = true;
    private int hoverHelperGridVisibilityHash = 0;

    private final Queue<Long> frametimes = new ArrayDeque<>();
    private long frametimeSum = 0L;
    private List<RenderHelper> cachedRenderData = List.of();
    private List<CachedStructureRender> cachedStructureRenderData = List.of();
    private RenderStateSnapshot lastRenderedState = null;
    private RenderStateSnapshot texturePassState = null;
    private RenderStateSnapshot lastQueuedState = null;
    private long lastTextureRefreshMs = 0L;
    private long lastQueueRefreshMs = 0L;
    private boolean forceTextureRefresh = true;
    private boolean textureRenderInProgress = false;
    private int textureRenderSectionCursor = 0;
    private int textureRenderXCursor = Integer.MIN_VALUE;
    private long lastTextureDataVersion = Long.MIN_VALUE;
    private long dragBurstUntilMs = 0L;
    private List<TextureDirtyRegion> textureDirtyRegions = List.of();
    private boolean textureDirtyIsFull = true;
    private boolean texturePixelsDirty = false;
    private FallbackTextureState fallbackTextureState = FallbackTextureState.NONE;
    private PreviewDisplayDataProvider.PlayerData cachedPlayerData = null;
    private long cachedPlayerDataAtMs = 0L;

    private boolean clicked = false;

    public PreviewDisplay(Minecraft minecraft, PreviewDisplayDataProvider dataProvider, Component component) {
        super(0, 0, 100, 100, component);
        this.minecraft = minecraft;
        this.workManager = WorldPreview.get().workManager();
        this.dataProvider = dataProvider;
        this.visibleBiomes = new Short2LongOpenHashMap();
        this.visibleStructures = new Short2LongOpenHashMap();
        this.renderSettings = WorldPreview.get().renderSettings();
        this.config = WorldPreview.get().cfg();
        this.iconTextureCache = new PreviewIconTextureCache(minecraft);
        PreviewAccelerationBackend.probeAndLog();
        this.structureIcons = new PreviewIconTextureCache.IconData[0];
        resizeImage();
    }

    public void resizeImage() {
        closeDisplayTextures();
        PreviewTextureRenderer.DisplayTexture displayTexture = PreviewTextureRenderer.createDisplayTexture(minecraft, texWidth, texHeight);
        previewImg = displayTexture.previewImage();
        panScratchImg = displayTexture.panScratchImage();
        previewTexture = displayTexture.texture();
        previewTextureId = displayTexture.textureId();
        scaleBlockPos = (QuartPos.SIZE / renderSettings.quartExpand()) * renderSettings.quartStride();
        hoverHelperGridWidth = (texWidth / hoverHelperGridCellSize) + 1;
        hoverHelperGridHeight = (texHeight / hoverHelperGridCellSize) + 1;
        hoverHelperGrid = new StructHoverHelperCell[hoverHelperGridWidth * hoverHelperGridHeight];
        for (int i = 0; i < hoverHelperGrid.length; ++i) {
            hoverHelperGrid[i] = new StructHoverHelperCell(new ArrayList<>());
        }
        hoverHelperGridDirty = true;
        cachedRenderData = List.of();
        cachedStructureRenderData = List.of();
        forceTextureRefresh = true;
        lastRenderedState = null;
        texturePassState = null;
        lastQueuedState = null;
        textureRenderInProgress = false;
        textureRenderSectionCursor = 0;
        textureRenderXCursor = Integer.MIN_VALUE;
        lastTextureDataVersion = Long.MIN_VALUE;
        dragBurstUntilMs = 0L;
        textureDirtyRegions = fullTextureDirtyRegions();
        textureDirtyIsFull = true;
        texturePixelsDirty = true;
        fallbackTextureState = FallbackTextureState.NONE;
    }

    public void setSize(int width, int height) {
        final int prevTexWidth = texWidth;
        final int prevTexHeight = texHeight;
        final int nextTexWidth = width * minecraft.getWindow().getGuiScale();
        final int nextTexHeight = height * minecraft.getWindow().getGuiScale();

        if (silentZoomInitialized && prevTexWidth > 0 && prevTexHeight > 0 && nextTexWidth > 0 && nextTexHeight > 0
                && (prevTexWidth != nextTexWidth || prevTexHeight != nextTexHeight)) {
            final double areaRatio = (nextTexWidth * (double) nextTexHeight) / (prevTexWidth * (double) prevTexHeight);
            if (Double.isFinite(areaRatio) && areaRatio > 0.0) {
                final double scaleCompensation = Math.sqrt(areaRatio);
                silentZoomFactor = Math.clamp(silentZoomFactor * scaleCompensation, MIN_SILENT_ZOOM_FACTOR, MAX_SILENT_ZOOM_FACTOR);
            }
        } else if (!silentZoomInitialized) {
            silentZoomInitialized = true;
        }

        this.width = width;
        this.height = height;
        this.texWidth = nextTexWidth;
        this.texHeight = nextTexHeight;
        resizeImage();
    }

    public void reloadData() {
        // Cleanup previous
        closeIconTextures();

        PreviewData.BiomeData[] rawBiomeMap = dataProvider.previewData().biomeId2BiomeData();
        structureRenderInfoMap = dataProvider.renderStructureMap();
        structureItems = dataProvider.structureItems();
        iconTextureCache.reload(dataProvider.structureIcons(), dataProvider.playerIcon(), dataProvider.spawnIcon());
        structureIcons = iconTextureCache.structureIcons();
        playerIcon = iconTextureCache.playerIcon();
        spawnIcon = iconTextureCache.spawnIcon();
        try {
            heightColorMap = dataProvider.heightColorMap();
            noiseColorMap = dataProvider.noiseColorMap();
        } catch (Throwable e) {
            WorldPreview.LOGGER.warn("Unable to load preview color maps", e);
        }
        workingVisibleBiomes = new long[rawBiomeMap.length];
        workingVisibleStructures = new long[structureIcons.length];
        colorMap = new int[rawBiomeMap.length];
        colorMapGrayScale = new int[rawBiomeMap.length];
        cavesMap = new boolean[rawBiomeMap.length];
        for (short i = 0; i < rawBiomeMap.length; ++i) {
            colorMap[i] = textureColor(rawBiomeMap[i].color());
            colorMapGrayScale[i] = grayScale(colorMap[i]);
            cavesMap[i] = rawBiomeMap[i].isCave();
        }
        forceTextureRefresh = true;
        lastRenderedState = null;
        texturePassState = null;
        cachedStructureRenderData = List.of();
        hoverHelperGridDirty = true;
        textureRenderInProgress = false;
        textureRenderSectionCursor = 0;
        textureRenderXCursor = Integer.MIN_VALUE;
        lastTextureDataVersion = Long.MIN_VALUE;
        dragBurstUntilMs = 0L;
        textureDirtyRegions = fullTextureDirtyRegions();
        textureDirtyIsFull = true;
    }

    private void closeIconTextures() {
        iconTextureCache.close();
        structureIcons = iconTextureCache.structureIcons();
        playerIcon = null;
        spawnIcon = null;
    }

    private void closeDisplayTextures() {
        PreviewTextureRenderer.closeDisplayTexture(minecraft, previewTextureId, panScratchImg);
        previewTextureId = null;
        previewTexture = null;
        previewImg = null;
        panScratchImg = null;
    }

    public void close() {
        closeIconTextures();
        closeDisplayTextures();
    }

    public BlockPos center() {
        if (totalDragX == 0 && totalDragZ == 0) {
            return renderSettings.center();
        }
        return new BlockPos(
                (int) (renderSettings.center().getX() + totalDragX),
                renderSettings.center().getY(),
                (int) (renderSettings.center().getZ() + totalDragZ)
        );
    }

    private boolean hasCoreRenderData() {
        return workingVisibleBiomes != null
                && colorMap != null
                && colorMapGrayScale != null
                && cavesMap != null;
    }

    @Override
    public void renderWidget(@NotNull GuiGraphics guiGraphics, int x, int y, float f) {
        final int colorBorder = 0xFF666666;

        final int xMin = getX();
        final int yMin = getY();
        final int xMax = xMin + width;
        final int yMax = yMin + height;

        final long renderStartNs = System.nanoTime();
        final long nowMs = System.currentTimeMillis();
        final RenderStateSnapshot currentState = currentRenderState();
        final boolean queueStateChanged = !currentState.equals(lastQueuedState);
        final long queueRefreshIntervalMs = queueStateChanged ? TEXTURE_REFRESH_MOVING_MS : GENERATION_QUEUE_REFRESH_MS;
        final boolean shouldQueueGeneration = forceTextureRefresh
                || (nowMs - lastQueueRefreshMs >= queueRefreshIntervalMs);
        if (shouldQueueGeneration) {
            queueGeneration();
            lastQueuedState = currentState;
            lastQueueRefreshMs = nowMs;
        }
        synchronized (dataProvider) {
            if (dataProvider.setupFailed()) {
                uploadFallbackTexture(FallbackTextureState.SETUP_FAILED);
                WorldPreviewClient.renderTexture(guiGraphics, previewTextureId, xMin, yMin, xMax, yMax);

                final List<MutableComponent> lines = MSG_ERROR_SETUP_FAILED.getString().lines().map(Component::literal).toList();

                final int centerX = getX() + (width / 2);
                final int centerY = getY() + (height / 2) - ((lines.size() / 2) * (minecraft.font.lineHeight + 4));

                for (int i = 0; i < lines.size(); ++i) {
                    final Component line = lines.get(i);
                    final int offsetY = i * (minecraft.font.lineHeight + 4);
                    guiGraphics.drawCenteredString(minecraft.font, line, centerX, centerY + offsetY, 0xFFFFFFFF);
                }
            } else if (dataProvider.isUpdating() || !hasCoreRenderData()) {
                uploadFallbackTexture(FallbackTextureState.LOADING);
                WorldPreviewClient.renderTexture(guiGraphics, previewTextureId, xMin, yMin, xMax, yMax);

                final int centerX = getX() + (width / 2);
                final int centerY = getY() + (height / 2);
                guiGraphics.drawCenteredString(minecraft.font, MSG_PREVIEW_SETUP_LOADING, centerX, centerY, 0xFFFFFFFF);
            } else {
                fallbackTextureState = FallbackTextureState.NONE;
                final boolean renderStateChanged = !currentState.equals(lastRenderedState);
                final boolean isDraggingMap = clicked && (Math.abs(totalDragX) > DRAG_CLICK_THRESHOLD_BLOCKS || Math.abs(totalDragZ) > DRAG_CLICK_THRESHOLD_BLOCKS);
                final long textureDataVersion = currentTextureDataVersion();
                final boolean textureDataChanged = textureDataVersion != lastTextureDataVersion;
                final long refreshIntervalMs = (textureRenderInProgress || renderStateChanged) ? TEXTURE_REFRESH_MOVING_MS : TEXTURE_REFRESH_IDLE_MS;
                final boolean shouldRefreshTexture = forceTextureRefresh
                        || renderStateChanged
                        || textureRenderInProgress
                        || cachedRenderData.isEmpty()
                        || (textureDataChanged && (nowMs - lastTextureRefreshMs >= refreshIntervalMs));

                boolean publishVisibility = false;
                if (shouldRefreshTexture) {
                    final boolean shouldRestartPass;
                    if (forceTextureRefresh) {
                        shouldRestartPass = true;
                    } else if (textureRenderInProgress) {
                        shouldRestartPass = isDraggingMap
                                && renderStateChanged
                                && (nowMs - lastTextureRefreshMs >= TEXTURE_REFRESH_MOVING_MS);
                    } else {
                        shouldRestartPass = true;
                    }
                    if (shouldRestartPass) {
                        final RenderStateSnapshot sourceTextureState = texturePassState != null ? texturePassState : lastRenderedState;
                        List<TextureDirtyRegion> dirtyRegions = fullTextureDirtyRegions();
                        if (renderStateChanged && !textureDataChanged && !forceTextureRefresh && !cachedRenderData.isEmpty()) {
                            final PanShiftResult panShiftResult = shiftTextureForPan(sourceTextureState, currentState);
                            if (panShiftResult != null) {
                                texturePixelsDirty = true;
                                final List<TextureDirtyRegion> carryOverDirtyRegions;
                                if (textureRenderInProgress) {
                                    carryOverDirtyRegions = shiftTextureDirtyRegions(textureDirtyRegions, panShiftResult.shiftX(), panShiftResult.shiftZ());
                                } else {
                                    carryOverDirtyRegions = List.of();
                                }
                                final List<TextureDirtyRegion> mergedDirtyRegions = new ArrayList<>(carryOverDirtyRegions.size() + panShiftResult.exposedRegions().size());
                                mergedDirtyRegions.addAll(carryOverDirtyRegions);
                                mergedDirtyRegions.addAll(panShiftResult.exposedRegions());
                                dirtyRegions = mergeTextureDirtyRegions(mergedDirtyRegions);
                            }
                        }

                        // Keep previous pixels as a visual base during non-pan state changes (e.g. Y/height changes),
                        // then incrementally overwrite dirty tiles to avoid black flashing.
                        final boolean fullTextureReset = forceTextureRefresh || cachedRenderData.isEmpty();
                        texturePassState = currentState;
                        if (renderStateChanged || textureDataChanged || forceTextureRefresh || cachedRenderData.isEmpty()) {
                            cachedRenderData = generateRenderData();
                        }
                        rebuildStructureRenderCache(cachedRenderData);
                        beginTextureRenderPass(cachedRenderData, fullTextureReset, dirtyRegions);
                        lastTextureDataVersion = textureDataVersion;
                    }

                    final long textureRenderBudgetNs;
                    if (nowMs < dragBurstUntilMs) {
                        textureRenderBudgetNs = TEXTURE_RENDER_BUDGET_DRAG_BURST_NS;
                    } else if (isDraggingMap) {
                        textureRenderBudgetNs = TEXTURE_RENDER_BUDGET_DRAGGING_NS;
                    } else {
                        textureRenderBudgetNs = TEXTURE_RENDER_BUDGET_NS;
                    }

                    final boolean texturePassCompleted = continueTextureRenderPass(cachedRenderData, textureRenderBudgetNs);
                    if (texturePixelsDirty) {
                        previewTexture.upload();
                        texturePixelsDirty = false;
                    }
                    lastTextureRefreshMs = nowMs;
                    forceTextureRefresh = false;
                    publishVisibility = texturePassCompleted;
                    if (texturePassCompleted) {
                        lastRenderedState = texturePassState != null ? texturePassState : currentState;
                        texturePassState = null;
                    }
                }

                // Render the main texture
                WorldPreviewClient.renderTexture(guiGraphics, previewTextureId, xMin, yMin, xMax, yMax);

                // Overlay structure icons
                guiGraphics.enableScissor(xMin, yMin, xMax, yMax);
                renderStructures(guiGraphics);
                renderPlayerAndSpawn(guiGraphics);
                guiGraphics.disableScissor();

                // Update hover info
                if (isHovered) {
                    refreshHoverHelperGridIfNeeded();
                }
                double mouseX = (minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth()) / minecraft.getWindow()
                        .getScreenWidth();
                double mouseZ = (minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight()) / minecraft.getWindow()
                        .getScreenHeight();

                if (publishVisibility) {
                    biomesChanged();
                }
                updateTooltip(guiGraphics, mouseX, mouseZ);
                renderProgressBars(guiGraphics, xMin, yMin);
            }
        }

        // Create a border
        guiGraphics.fill(xMin-1, yMin-1, xMax+1, yMin, colorBorder); // Right
        guiGraphics.fill(xMax, yMin, xMax+1, yMax, colorBorder); // Down
        guiGraphics.fill(xMin-1, yMax, xMax+1, yMax+1, colorBorder); // Left
        guiGraphics.fill(xMin-1, yMin, xMin, yMax, colorBorder); // Up

        // Render copied message
        if (coordinatesCopiedMsg != null) {
            guiGraphics.fill(xMin, yMax - 38, xMax, yMax - 19, 0xAA000000);
            guiGraphics.drawCenteredString(minecraft.font, coordinatesCopiedMsg, xMin + ((xMax - xMin) / 2), yMax - 32, 0xFFFFFFFF);
            if (nowMs - coordinatesCopiedAtMs >= 8000L) {
                coordinatesCopiedMsg = null;
                coordinatesCopiedAtMs = 0L;
            }
        }

        final String zoomLabel = formatZoomLabel();
        final int zoomTextWidth = minecraft.font.width(zoomLabel);
        final int zoomBoxHeight = minecraft.font.lineHeight + 6;
        final int zoomBoxWidth = zoomTextWidth + 8;
        final int zoomXMax = xMax - 4;
        final int zoomXMin = zoomXMax - zoomBoxWidth;
        final int zoomYMax = yMax - 4;
        final int zoomYMin = zoomYMax - zoomBoxHeight;
        guiGraphics.fill(zoomXMin, zoomYMin, zoomXMax, zoomYMax, 0xAA000000);
        guiGraphics.drawString(minecraft.font, zoomLabel, zoomXMin + 4, zoomYMin + 3, 0xFFFFFFFF);

        final long frameTimeMs = Math.max(0L, (System.nanoTime() - renderStartNs) / 1_000_000L);
        frametimes.add(frameTimeMs);
        frametimeSum += frameTimeMs;
        while (frametimes.size() > 30) {
            final Long removed = frametimes.poll();
            if (removed != null) {
                frametimeSum -= removed;
            }
        }

        if (config.showFrameTime && !frametimes.isEmpty()) {
            guiGraphics.drawString(minecraft.font, frametimeSum / frametimes.size() + " ms", 5, 5, 0xFFFFFFFF);
        }
    }

    private record TextureCoordinate(int x, int z) {}

    private record TextureDirtyRegion(int xMin, int zMin, int xMax, int zMax) {
        boolean intersects(int oXMin, int oZMin, int oXMax, int oZMax) {
            return oXMin < xMax && oXMax > xMin && oZMin < zMax && oZMax > zMin;
        }
    }

    private record PanShiftResult(int shiftX, int shiftZ, List<TextureDirtyRegion> exposedRegions) {
    }

    private enum FallbackTextureState {
        NONE,
        SETUP_FAILED,
        LOADING
    }

    private record RenderStateSnapshot(
            int centerX,
            int centerY,
            int centerZ,
            int texWidth,
            int texHeight,
            long zoomBits,
            int modeOrdinal,
            int quartExpand,
            int quartStride,
            short selectedBiomeId,
            boolean highlightCaves
    ) {
    }

    private RenderStateSnapshot currentRenderState() {
        final BlockPos currentCenter = center();
        return new RenderStateSnapshot(
                currentCenter.getX(),
                currentCenter.getY(),
                currentCenter.getZ(),
                texWidth,
                texHeight,
                Double.doubleToLongBits(zoomFactor),
                renderSettings.mode.ordinal(),
                renderSettings.quartExpand(),
                renderSettings.quartStride(),
                selectedBiomeId,
                highlightCaves
        );
    }

    private long currentTextureDataVersion() {
        return workManager.renderDataVersion();
    }

    private void uploadFallbackTexture(FallbackTextureState state) {
        if (fallbackTextureState == state) {
            return;
        }
        previewImg.fillRect(0, 0, texWidth, texHeight, 0xFF000000);
        previewTexture.upload();
        texturePixelsDirty = false;
        fallbackTextureState = state;
    }

    private void renderProgressBars(GuiGraphics guiGraphics, int xMin, int yMin) {
        final float structProgress = workManager.structureGenerationProgress();
        if (structProgress >= 0.999f) {
            return;
        }

        final int barX = xMin + 6;
        final int barY = yMin + 6;
        final int barWidth = Math.clamp(width - 12, 60, PROGRESS_BAR_WIDTH);
        final int panelWidth = barWidth + 8;
        final int panelHeight = 20;
        final int structFill = Math.clamp(Math.round(barWidth * structProgress), 0, barWidth);

        guiGraphics.fill(barX - 4, barY - 4, barX - 4 + panelWidth, barY - 4 + panelHeight, 0x88000000);
        guiGraphics.drawString(minecraft.font, "Struct", barX, barY - 1, 0xFFFFFFFF);
        guiGraphics.fill(barX, barY + 8, barX + barWidth, barY + 8 + PROGRESS_BAR_HEIGHT, 0xFF2A2A2A);
        guiGraphics.fill(barX, barY + 8, barX + structFill, barY + 8 + PROGRESS_BAR_HEIGHT, 0xFF42A5F5);
    }

    private List<TextureDirtyRegion> fullTextureDirtyRegions() {
        return List.of(new TextureDirtyRegion(0, 0, texWidth, texHeight));
    }

    private TextureDirtyRegion clipTextureDirtyRegion(TextureDirtyRegion region) {
        final int xMin = Math.clamp(region.xMin(), 0, texWidth);
        final int zMin = Math.clamp(region.zMin(), 0, texHeight);
        final int xMax = Math.clamp(region.xMax(), 0, texWidth);
        final int zMax = Math.clamp(region.zMax(), 0, texHeight);
        if (xMax <= xMin || zMax <= zMin) {
            return null;
        }
        return new TextureDirtyRegion(xMin, zMin, xMax, zMax);
    }

    private boolean dirtyRegionsTouchOrOverlap(TextureDirtyRegion a, TextureDirtyRegion b) {
        return a.xMin() <= b.xMax() && a.xMax() >= b.xMin()
                && a.zMin() <= b.zMax() && a.zMax() >= b.zMin();
    }

    private List<TextureDirtyRegion> mergeTextureDirtyRegions(List<TextureDirtyRegion> dirtyRegions) {
        if (dirtyRegions.isEmpty()) {
            return List.of();
        }
        final List<TextureDirtyRegion> merged = new ArrayList<>();
        for (TextureDirtyRegion regionRaw : dirtyRegions) {
            TextureDirtyRegion region = clipTextureDirtyRegion(regionRaw);
            if (region == null) {
                continue;
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int i = 0; i < merged.size(); ++i) {
                    final TextureDirtyRegion existing = merged.get(i);
                    if (dirtyRegionsTouchOrOverlap(existing, region)) {
                        region = new TextureDirtyRegion(
                                Math.min(existing.xMin(), region.xMin()),
                                Math.min(existing.zMin(), region.zMin()),
                                Math.max(existing.xMax(), region.xMax()),
                                Math.max(existing.zMax(), region.zMax())
                        );
                        merged.remove(i);
                        changed = true;
                        break;
                    }
                }
            }
            merged.add(region);
            if (merged.size() > 8) {
                return fullTextureDirtyRegions();
            }
        }
        return merged.isEmpty() ? List.of() : merged;
    }

    private List<TextureDirtyRegion> shiftTextureDirtyRegions(List<TextureDirtyRegion> dirtyRegions, int shiftX, int shiftZ) {
        if (dirtyRegions.isEmpty()) {
            return List.of();
        }
        final List<TextureDirtyRegion> shiftedRegions = new ArrayList<>(dirtyRegions.size());
        for (TextureDirtyRegion region : dirtyRegions) {
            shiftedRegions.add(new TextureDirtyRegion(
                    region.xMin() - shiftX,
                    region.zMin() - shiftZ,
                    region.xMax() - shiftX,
                    region.zMax() - shiftZ
            ));
        }
        return mergeTextureDirtyRegions(shiftedRegions);
    }

    private void setTextureDirtyRegions(List<TextureDirtyRegion> dirtyRegions) {
        textureDirtyRegions = dirtyRegions;
        if (dirtyRegions.size() != 1) {
            textureDirtyIsFull = false;
            return;
        }
        final TextureDirtyRegion firstRegion = dirtyRegions.getFirst();
        textureDirtyIsFull = firstRegion.xMin == 0
                && firstRegion.zMin == 0
                && firstRegion.xMax == texWidth
                && firstRegion.zMax == texHeight;
    }

    private boolean intersectsTextureDirtyRegion(int xMin, int zMin, int xMax, int zMax) {
        if (textureDirtyIsFull) {
            return true;
        }
        for (TextureDirtyRegion region : textureDirtyRegions) {
            if (region.intersects(xMin, zMin, xMax, zMax)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPanOnlyStateChange(RenderStateSnapshot fromState, RenderStateSnapshot toState) {
        if (fromState == null || toState == null) {
            return false;
        }
        if (fromState.centerX == toState.centerX && fromState.centerZ == toState.centerZ) {
            return false;
        }
        return fromState.centerY == toState.centerY
                && fromState.texWidth == toState.texWidth
                && fromState.texHeight == toState.texHeight
                && fromState.zoomBits == toState.zoomBits
                && fromState.modeOrdinal == toState.modeOrdinal
                && fromState.quartExpand == toState.quartExpand
                && fromState.quartStride == toState.quartStride
                && fromState.selectedBiomeId == toState.selectedBiomeId
                && fromState.highlightCaves == toState.highlightCaves;
    }

    private PanShiftResult shiftTextureForPan(RenderStateSnapshot fromState, RenderStateSnapshot toState) {
        if (panScratchImg == null || previewImg == null || !isPanOnlyStateChange(fromState, toState)) {
            return null;
        }

        final double effectiveScale = effectiveScaleBlockPos(toState);
        if (effectiveScale <= 0.0) {
            return null;
        }

        final double shiftXExact = (toState.centerX - fromState.centerX) / effectiveScale;
        final double shiftZExact = (toState.centerZ - fromState.centerZ) / effectiveScale;
        final int shiftX = (int) Math.round(shiftXExact);
        final int shiftZ = (int) Math.round(shiftZExact);
        if (Math.abs(shiftX) >= texWidth || Math.abs(shiftZ) >= texHeight) {
            return null;
        }

        final int copyWidth = texWidth - Math.abs(shiftX);
        final int copyHeight = texHeight - Math.abs(shiftZ);
        if (copyWidth <= 0 || copyHeight <= 0) {
            return null;
        }

        final int srcX = Math.max(0, shiftX);
        final int srcZ = Math.max(0, shiftZ);
        final int dstX = Math.max(0, -shiftX);
        final int dstZ = Math.max(0, -shiftZ);

        panScratchImg.fillRect(0, 0, texWidth, texHeight, 0xFF000000);
        previewImg.copyRect(panScratchImg, srcX, srcZ, dstX, dstZ, copyWidth, copyHeight, false, false);
        previewImg.copyFrom(panScratchImg);

        final List<TextureDirtyRegion> dirtyRegions = new ArrayList<>(2);
        if (shiftX > 0) {
            dirtyRegions.add(new TextureDirtyRegion(texWidth - shiftX, 0, texWidth, texHeight));
        } else if (shiftX < 0) {
            dirtyRegions.add(new TextureDirtyRegion(0, 0, -shiftX, texHeight));
        }
        if (shiftZ > 0) {
            dirtyRegions.add(new TextureDirtyRegion(0, texHeight - shiftZ, texWidth, texHeight));
        } else if (shiftZ < 0) {
            dirtyRegions.add(new TextureDirtyRegion(0, 0, texWidth, -shiftZ));
        }

        return new PanShiftResult(shiftX, shiftZ, mergeTextureDirtyRegions(dirtyRegions));
    }

    private String formatZoomLabel() {
        return PreviewViewport.formatZoomLabel(zoomFactor);
    }

    private double effectiveScaleBlockPos() {
        return PreviewViewport.effectiveScaleBlockPos(scaleBlockPos, zoomFactor, silentZoomFactor);
    }

    private double effectiveScaleBlockPos(RenderStateSnapshot state) {
        final double zoom = Double.longBitsToDouble(state.zoomBits);
        if (zoom <= 0.0) {
            return 1.0;
        }
        final int stateScaleBlockPos = (QuartPos.SIZE / state.quartExpand) * state.quartStride;
        return PreviewViewport.effectiveScaleBlockPos(stateScaleBlockPos, zoom, silentZoomFactor);
    }

    private int minBlockX(BlockPos center) {
        return PreviewViewport.minBlock(center.getX(), texWidth, effectiveScaleBlockPos());
    }

    private int minBlockX(RenderStateSnapshot state) {
        return PreviewViewport.minBlock(state.centerX, state.texWidth, effectiveScaleBlockPos(state));
    }

    private int maxBlockX(BlockPos center) {
        return PreviewViewport.maxBlock(center.getX(), texWidth, effectiveScaleBlockPos());
    }

    private int minBlockZ(BlockPos center) {
        return PreviewViewport.minBlock(center.getZ(), texHeight, effectiveScaleBlockPos());
    }

    private int minBlockZ(RenderStateSnapshot state) {
        return PreviewViewport.minBlock(state.centerZ, state.texHeight, effectiveScaleBlockPos(state));
    }

    private int maxBlockZ(BlockPos center) {
        return PreviewViewport.maxBlock(center.getZ(), texHeight, effectiveScaleBlockPos());
    }

    private RenderSettings.RenderMode renderMode(RenderStateSnapshot state) {
        final int modeOrdinal = state.modeOrdinal;
        if (modeOrdinal < 0 || modeOrdinal >= RENDER_MODES.length) {
            return RenderSettings.RenderMode.BIOMES;
        }
        return RENDER_MODES[modeOrdinal];
    }

    private TextureCoordinate blockToTexture(BlockPos blockPos) {
        BlockPos center = center();
        final int xMin = minBlockX(center);
        final int zMin = minBlockZ(center);
        final double effectiveScale = effectiveScaleBlockPos();

        return new TextureCoordinate(
                PreviewViewport.blockToTexture(blockPos.getX(), xMin, effectiveScale),
                PreviewViewport.blockToTexture(blockPos.getZ(), zMin, effectiveScale)
        );
    }

    private void putHoverStructEntry(TextureCoordinate pos, StructHoverHelperEntry entry) {
        int cellX = Math.clamp(pos.x / hoverHelperGridCellSize, 0, hoverHelperGridWidth - 1);
        int cellZ = Math.clamp(pos.z / hoverHelperGridCellSize, 0, hoverHelperGridHeight - 1);
        hoverHelperGrid[(cellX * hoverHelperGridHeight) + cellZ].entries.add(entry);
    }

    private void clearHoverHelperGrid() {
        for (StructHoverHelperCell cell : hoverHelperGrid) {
            cell.entries.clear();
        }
    }

    private int currentHoverGridVisibilityHash() {
        int hash = Boolean.hashCode(renderSettings.hideAllStructures);
        hash = 31 * hash + cachedStructureRenderData.size();
        if (structureRenderInfoMap == null) {
            return hash;
        }
        for (CachedStructureRender entry : cachedStructureRenderData) {
            final short id = entry.structureId();
            hash = 31 * hash + id;
            hash = 31 * hash + (id >= 0 && id < structureRenderInfoMap.length && structureRenderInfoMap[id].show() ? 1 : 0);
        }
        return hash;
    }

    private void refreshHoverHelperGridIfNeeded() {
        final int visibilityHash = currentHoverGridVisibilityHash();
        if (!hoverHelperGridDirty && hoverHelperGridVisibilityHash == visibilityHash) {
            return;
        }

        clearHoverHelperGrid();
        hoverHelperGridVisibilityHash = visibilityHash;
        hoverHelperGridDirty = false;

        if (!config.sampleStructures || renderSettings.hideAllStructures || structureRenderInfoMap == null) {
            return;
        }

        for (CachedStructureRender entry : cachedStructureRenderData) {
            final short id = entry.structureId();
            if (id < 0 || id >= structureRenderInfoMap.length || !structureRenderInfoMap[id].show()) {
                continue;
            }
            putHoverStructEntry(
                    new TextureCoordinate(entry.texCenterX(), entry.texCenterZ()),
                    new StructHoverHelperEntry(
                            new BoundingBox(
                                    entry.texStartX(),
                                    0,
                                    entry.texStartZ(),
                                    entry.texStartX() + entry.iconWidth(),
                                    0,
                                    entry.texStartZ() + entry.iconHeight()
                            ),
                            entry.structure()
                    )
            );
        }
    }

    private void queueGeneration() {
        final BlockPos center = center();
        int xMin = minBlockX(center);
        int xMax = maxBlockX(center);
        int zMin = minBlockZ(center);
        int zMax = maxBlockZ(center);
        final int y = center.getY();

        // While dragging, predict near-future trajectory and queue that direction too.
        if (clicked) {
            final double speed = Math.hypot(dragVelocityBlocksX, dragVelocityBlocksZ);
            if (speed >= DRAG_PREDICTION_MIN_SPEED_BLOCKS_PER_SEC) {
                final double lookAheadSeconds = DRAG_PREDICTION_HORIZON_MS / 1000.0;
                final double maxAhead = Math.max(texWidth, texHeight) * effectiveScaleBlockPos() * DRAG_PREDICTION_MAX_VIEW_MULTIPLIER;
                final int predictedOffsetX = (int) Math.round(Math.clamp(dragVelocityBlocksX * lookAheadSeconds, -maxAhead, maxAhead));
                final int predictedOffsetZ = (int) Math.round(Math.clamp(dragVelocityBlocksZ * lookAheadSeconds, -maxAhead, maxAhead));
                if (predictedOffsetX != 0 || predictedOffsetZ != 0) {
                    final BlockPos predictedCenter = new BlockPos(center.getX() + predictedOffsetX, y, center.getZ() + predictedOffsetZ);
                    xMin = Math.min(xMin, minBlockX(predictedCenter));
                    xMax = Math.max(xMax, maxBlockX(predictedCenter));
                    zMin = Math.min(zMin, minBlockZ(predictedCenter));
                    zMax = Math.max(zMax, maxBlockZ(predictedCenter));
                }
            }
        }

        workManager.queueRange(new BlockPos(xMin, y, zMin), new BlockPos(xMax, y, zMax));
    }

    private record RenderHelper(
            @Nullable PreviewSection dataSection,
            @Nullable PreviewSection structureSection,
            int sectionQuartX,
            int sectionQuartZ,
            PreviewSection.AccessData accessData
    ) {
    }

    private record CachedStructureRender(
            short structureId,
            PreviewSection.PreviewStruct structure,
            Identifier iconTexture,
            ItemStack item,
            int texCenterX,
            int texCenterZ,
            int texStartX,
            int texStartZ,
            int iconWidth,
            int iconHeight,
            int baseDrawX,
            int baseDrawZ,
            int baseDrawWidth,
            int baseDrawHeight
    ) {
    }

    private static int sectionQuartBase(int quart) {
        return quart & PreviewSection.MASK;
    }

    private static PreviewSection.AccessData calcSectionAccessData(int sectionQuartX, int sectionQuartZ, int minQuartX, int minQuartZ, int maxQuartX, int maxQuartZ) {
        final int accessMinX = minQuartX - sectionQuartX;
        final int accessMinZ = minQuartZ - sectionQuartZ;
        final int accessMaxX = maxQuartX - sectionQuartX;
        final int accessMaxZ = maxQuartZ - sectionQuartZ;
        return new PreviewSection.AccessData(
                accessMinX,
                accessMinZ,
                Math.min(accessMaxX, PreviewSection.SIZE),
                Math.min(accessMaxZ, PreviewSection.SIZE),
                accessMaxX > PreviewSection.SIZE,
                accessMaxZ > PreviewSection.SIZE
        );
    }

    private List<RenderHelper> generateRenderData() {
        final PreviewStorage storage = workManager.previewStorage();
        if (storage == null) {
            return List.of();
        }

        final BlockPos center = center();
        final int xMin = minBlockX(center);
        final int xMax = maxBlockX(center);
        final int zMin = minBlockZ(center);
        final int zMax = maxBlockZ(center);

        final int quartStride = renderSettings.quartStride();

        final int minQuartX = QuartPos.fromBlock(xMin);
        final int minQuartZ = QuartPos.fromBlock(zMin);

        final int maxQuartX = QuartPos.fromBlock(xMax);
        final int maxQuartZ = QuartPos.fromBlock(zMax);
        final int quartsInWidth = Math.max(quartStride, maxQuartX - minQuartX);
        final int quartsInHeight = Math.max(quartStride, maxQuartZ - minQuartZ);

        int quartX = minQuartX;
        int quartY = QuartPos.fromBlock(center.getY());
        int quartZ = minQuartZ;

        final List<RenderHelper> res = new ArrayList<>(((quartsInWidth / PreviewSection.SIZE) + 2) * ((quartsInHeight / PreviewSection.SIZE) + 2));

        // Load existing sections only. Missing data renders as the already-cleared black background.
        while (true) {
            long flag = renderSettings.mode.flag;
            int useY = renderSettings.mode.useY ? quartY : 0;
            PreviewSection dataSection = storage.getExistingSection4(quartX, useY, quartZ, flag);
            PreviewSection structureSection = storage.getExistingSection4(quartX, 0, quartZ, PreviewStorage.FLAG_STRUCT_START);
            int sectionQuartX = dataSection != null ? dataSection.quartX() : sectionQuartBase(quartX);
            int sectionQuartZ = dataSection != null ? dataSection.quartZ() : sectionQuartBase(quartZ);
            PreviewSection.AccessData accessData = calcSectionAccessData(sectionQuartX, sectionQuartZ, quartX, quartZ, maxQuartX, maxQuartZ);

            if (dataSection != null || structureSection != null) {
                res.add(new RenderHelper(dataSection, structureSection, sectionQuartX, sectionQuartZ, accessData));
            }

            // Can we fit more stuff in the X direction?
            if (accessData.continueX()) {
                int quartDiffX = accessData.maxX() - accessData.minX();
                quartX += quartDiffX;
                continue;
            }

            // We are at the end in the X direction, can we continue in the Z direction?
            if (accessData.continueZ()) {
                int quartDiffZ = accessData.maxZ() - accessData.minZ();
                quartX = minQuartX;
                quartZ += quartDiffZ;
                continue;
            }

            // We are done drawing now
            break;
        }

        return res;
    }

    private void beginTextureRenderPass(List<RenderHelper> renderData, boolean clearTexture, List<TextureDirtyRegion> dirtyRegions) {
        if (clearTexture) {
            previewImg.fillRect(0, 0, texWidth, texHeight, 0xFF000000);
            texturePixelsDirty = true;
        }
        setTextureDirtyRegions(dirtyRegions.isEmpty() ? List.of() : dirtyRegions);
        Arrays.fill(workingVisibleBiomes, 0L);
        textureRenderSectionCursor = 0;
        textureRenderXCursor = Integer.MIN_VALUE;
        textureRenderInProgress = !renderData.isEmpty();
    }

    private boolean continueTextureRenderPass(List<RenderHelper> renderData, long budgetNs) {
        if (renderData.isEmpty()) {
            textureRenderInProgress = false;
            return true;
        }

        final RenderStateSnapshot passState = texturePassState != null ? texturePassState : currentRenderState();
        final int quartStride = passState.quartStride;
        final int blockStride = quartStride * QuartPos.SIZE;
        final int xMin = minBlockX(passState);
        final int zMin = minBlockZ(passState);
        final double effectiveScale = effectiveScaleBlockPos(passState);
        final var mode = renderMode(passState);
        final long deadline = System.nanoTime() + budgetNs;

        while (textureRenderSectionCursor < renderData.size()) {
            RenderHelper r = renderData.get(textureRenderSectionCursor);
            if (r.dataSection == null) {
                textureRenderSectionCursor += 1;
                textureRenderXCursor = Integer.MIN_VALUE;
                continue;
            }
            if (mode != RenderSettings.RenderMode.BIOMES && !textureDirtyIsFull && textureRenderXCursor == Integer.MIN_VALUE) {
                final int sectionBlockXMin = QuartPos.toBlock(r.sectionQuartX() + r.accessData.minX());
                final int sectionBlockXMax = QuartPos.toBlock(r.sectionQuartX() + r.accessData.maxX());
                final int sectionBlockZMin = QuartPos.toBlock(r.sectionQuartZ() + r.accessData.minZ());
                final int sectionBlockZMax = QuartPos.toBlock(r.sectionQuartZ() + r.accessData.maxZ());
                final int sectionTexXMin = Math.max(0, (int) Math.floor((sectionBlockXMin - xMin) / effectiveScale));
                final int sectionTexXMax = Math.min(texWidth, (int) Math.ceil((sectionBlockXMax - xMin) / effectiveScale));
                final int sectionTexZMin = Math.max(0, (int) Math.floor((sectionBlockZMin - zMin) / effectiveScale));
                final int sectionTexZMax = Math.min(texHeight, (int) Math.ceil((sectionBlockZMax - zMin) / effectiveScale));
                if (!intersectsTextureDirtyRegion(sectionTexXMin, sectionTexZMin, sectionTexXMax, sectionTexZMax)) {
                    textureRenderSectionCursor += 1;
                    textureRenderXCursor = Integer.MIN_VALUE;
                    continue;
                }
            }
            int startX = textureRenderXCursor == Integer.MIN_VALUE ? r.accessData.minX() : textureRenderXCursor;
            for (int x = startX; x < r.accessData.maxX(); x += quartStride) {
                final int blockStartX = QuartPos.toBlock(r.sectionQuartX() + x);
                int texXMin = (int) Math.floor((blockStartX - xMin) / effectiveScale);
                int texXMax = (int) Math.ceil((blockStartX + blockStride - xMin) / effectiveScale);
                if (texXMax <= 0 || texXMin >= texWidth) {
                    continue;
                }
                texXMin = Math.max(texXMin, 0);
                texXMax = Math.min(texXMax, texWidth);
                final int texXSize = texXMax - texXMin;
                if (texXSize <= 0) {
                    continue;
                }
                for (int z = r.accessData.minZ(); z < r.accessData.maxZ(); z += quartStride) {
                    final int blockStartZ = QuartPos.toBlock(r.sectionQuartZ() + z);
                    int texZMin = (int) Math.floor((blockStartZ - zMin) / effectiveScale);
                    int texZMax = (int) Math.ceil((blockStartZ + blockStride - zMin) / effectiveScale);
                    if (texZMax <= 0 || texZMin >= texHeight) {
                        continue;
                    }
                    texZMin = Math.max(texZMin, 0);
                    texZMax = Math.min(texZMax, texHeight);
                    final int texZSize = texZMax - texZMin;
                    if (texZSize <= 0) {
                        continue;
                    }

                    final boolean needsDraw = intersectsTextureDirtyRegion(texXMin, texZMin, texXMax, texZMax);
                    int color = 0xFF000000;
                    switch (mode) {
                        case BIOMES -> {
                            short rawData = r.dataSection.get(x, z);
                            if (rawData >= 0) {
                                workingVisibleBiomes[rawData] += 1;
                                if (needsDraw) {
                                    color = selectedBiomeId >= 0 || highlightCaves ? colorMapGrayScale[rawData] : colorMap[rawData];
                                    if (selectedBiomeId == rawData || (highlightCaves && cavesMap[rawData])) {
                                        color = colorMap[rawData];
                                    }
                                }
                            }
                        }
                        case HEIGHTMAP -> {
                            if (!needsDraw) {
                                continue;
                            }
                            short rawData = r.dataSection.get(x, z);
                            if (rawData > Short.MIN_VALUE) {
                                color = heightColorMap[rawData - dataProvider.yMin()];
                            }
                        }
                        case INTERSECTIONS -> {
                            if (!needsDraw) {
                                continue;
                            }
                            short rawData = r.dataSection.get(x, z);
                            if (rawData >= 0) {
                                // Main y-intersection
                                color = MapColor.byId(rawData).col;
                                color = textureColor(color == 0 ? 0xFFFFFF : color);
                            } else if(rawData > Short.MIN_VALUE) {
                                // See through one layer of air
                                color = MapColor.byId(-rawData).col;
                                color = highlightColor(textureColor(color == 0 ? 0xFFFFFF : color));
                            }
                        }
                        case NOISE_TEMPERATURE, NOISE_HUMIDITY, NOISE_CONTINENTALNESS, NOISE_EROSION, NOISE_DEPTH, NOISE_WEIRDNESS -> {
                            if (!needsDraw) {
                                continue;
                            }
                            short rawData = r.dataSection.get(x, z);
                            if (rawData > Short.MIN_VALUE) {
                                final float data = ((float) rawData) / ((float) Short.MAX_VALUE);
                                final int idx = Math.clamp(512 + (int) (data * 512), 0, 1023);
                                color = noiseColorMap[idx];
                            }
                        }
                        case NOISE_PEAKS_AND_VALLEYS -> {
                            if (!needsDraw) {
                                continue;
                            }
                            short rawData = r.dataSection.get(x, z);
                            if (rawData > Short.MIN_VALUE) {
                                final float data = ((float) rawData) / 0.75f / ((float) Short.MAX_VALUE);
                                final float pvData = NoiseRouterData.peaksAndValleys(Math.clamp(data, -1.0f, 1.0f));
                                final int idx = Math.clamp(512 + (int) (pvData * 512), 0, 1023);
                                color = noiseColorMap[idx];
                            }
                        }
                    }

                    if (needsDraw) {
                        previewImg.fillRect(texXMin, texZMin, texXSize, texZSize, color);
                        texturePixelsDirty = true;
                    }
                }
                if (System.nanoTime() >= deadline) {
                    textureRenderXCursor = x + quartStride;
                    textureRenderInProgress = true;
                    return false;
                }
            }
            textureRenderSectionCursor += 1;
            textureRenderXCursor = Integer.MIN_VALUE;
        }
        textureRenderInProgress = false;
        return true;
    }

    private void rebuildStructureRenderCache(List<RenderHelper> renderData) {
        if (workingVisibleStructures == null || structureIcons == null || structureItems == null || structureRenderInfoMap == null) {
            cachedStructureRenderData = List.of();
            hoverHelperGridDirty = true;
            return;
        }

        Arrays.fill(workingVisibleStructures, 0L);
        if (!config.sampleStructures || renderData.isEmpty()) {
            cachedStructureRenderData = List.of();
            hoverHelperGridDirty = true;
            return;
        }

        final double guiScale = minecraft.getWindow().getGuiScale();
        final double invGuiScale = 1.0 / guiScale;
        final int targetIconSizeTex = Math.max(1, (int) Math.round(STRUCTURE_ICON_TARGET_SIZE_GUI * guiScale));

        final BlockPos center = center();
        final int xMin = minBlockX(center);
        final int zMin = minBlockZ(center);
        final double effectiveScale = effectiveScaleBlockPos();

        final List<CachedStructureRender> structureCache = new ArrayList<>();
        for (RenderHelper r : renderData) {
            if (r.structureSection == null) {
                continue;
            }
            for (PreviewSection.PreviewStruct structure : r.structureSection.structures()) {
                final short id = structure.structureId();
                if (id < 0 || id >= structureIcons.length || id >= structureItems.length || id >= structureRenderInfoMap.length) {
                    continue;
                }

                final int texCenterX = (int) Math.floor((structure.center().getX() - xMin) / effectiveScale);
                final int texCenterZ = (int) Math.floor((structure.center().getZ() - zMin) / effectiveScale);
                final PreviewIconTextureCache.IconData iconData = structureIcons[id];
                final Identifier iconTexture = iconData != null ? iconData.textureId() : null;
                final ItemStack item = structureItems[id];
                if (iconTexture == null && item == null) {
                    continue;
                }

                final int iconWidth;
                final int iconHeight;
                if (item != null) {
                    iconWidth = targetIconSizeTex;
                    iconHeight = targetIconSizeTex;
                } else {
                    final int rawIconWidth = iconData.width();
                    final int rawIconHeight = iconData.height();
                    final double iconScale = targetIconSizeTex / (double) Math.max(rawIconWidth, rawIconHeight);
                    iconWidth = Math.max(1, (int) Math.round(rawIconWidth * iconScale));
                    iconHeight = Math.max(1, (int) Math.round(rawIconHeight * iconScale));
                }

                final int visibleMinX = -(iconWidth / 2);
                final int visibleMaxX = (iconWidth / 2) + 1 + texWidth;
                final int visibleMinZ = -(iconHeight / 2);
                final int visibleMaxZ = (iconHeight / 2) + 1 + texHeight;
                if (texCenterX < visibleMinX || texCenterZ < visibleMinZ || texCenterX > visibleMaxX || texCenterZ > visibleMaxZ) {
                    continue;
                }

                workingVisibleStructures[id] += 1;

                final int texStartX = texCenterX - (iconWidth / 2);
                final int texStartZ = texCenterZ - (iconHeight / 2);
                final int baseDrawX = getX() + (int) Math.round(texStartX * invGuiScale);
                final int baseDrawZ = getY() + (int) Math.round(texStartZ * invGuiScale);
                final int baseDrawWidth = Math.max(1, (int) Math.round(iconWidth * invGuiScale));
                final int baseDrawHeight = Math.max(1, (int) Math.round(iconHeight * invGuiScale));
                structureCache.add(new CachedStructureRender(
                        id, structure, iconTexture, item, texCenterX, texCenterZ,
                        texStartX, texStartZ, iconWidth, iconHeight,
                        baseDrawX, baseDrawZ, baseDrawWidth, baseDrawHeight
                ));
            }
        }
        cachedStructureRenderData = structureCache;
        hoverHelperGridDirty = true;
    }

    private void renderStructures(GuiGraphics guiGraphics) {
        if (!config.sampleStructures || cachedStructureRenderData.isEmpty()) {
            return;
        }

        final int selectedStructureId = dataProvider.selectedStructureId();
        final long nowMs = System.currentTimeMillis();
        final double phase = ((nowMs % SELECTED_STRUCTURE_PULSE_MS) / (double) SELECTED_STRUCTURE_PULSE_MS) * (Math.PI * 2.0);
        final float selectedBobOffsetGui = SELECTED_STRUCTURE_BOB_PIXELS_GUI * (float) Math.sin(phase);
        final boolean hideAllStructures = renderSettings.hideAllStructures;

        for (CachedStructureRender entry : cachedStructureRenderData) {
            final short id = entry.structureId();
            if (hideAllStructures || !structureRenderInfoMap[id].show()) {
                continue;
            }

            final int bobOffsetGui = id == selectedStructureId ? Math.round(selectedBobOffsetGui) : 0;
            final int drawWidth = entry.baseDrawWidth();
            final int drawHeight = entry.baseDrawHeight();
            final int drawXMin = entry.baseDrawX();
            final int drawZMin = entry.baseDrawZ() + bobOffsetGui;
            final int drawXMax = drawXMin + drawWidth;
            final int drawZMax = drawZMin + drawHeight;

            if (entry.item() != null) {
                guiGraphics.pose().pushMatrix();
                guiGraphics.pose().translate(drawXMin, drawZMin);
                guiGraphics.pose().scale(drawWidth / 16f, drawHeight / 16f);
                guiGraphics.renderItem(entry.item(), 0, 0);
                guiGraphics.pose().popMatrix();
            } else if (entry.iconTexture() != null) {
                WorldPreviewClient.renderTexture(guiGraphics, entry.iconTexture(), drawXMin, drawZMin, drawXMax, drawZMax);
            }
        }
    }

    private void renderPlayerAndSpawn(GuiGraphics guiGraphics) {
        if (!config.showPlayer) {
            return;
        }

        final long nowMs = System.currentTimeMillis();
        if (cachedPlayerData == null || nowMs - cachedPlayerDataAtMs >= PLAYER_MARKER_REFRESH_MS) {
            cachedPlayerData = dataProvider.getPlayerData(minecraft.getUser().getProfileId());
            cachedPlayerDataAtMs = nowMs;
        }
        PreviewDisplayDataProvider.PlayerData playerData = cachedPlayerData;
        if (playerData.currentPos() != null) {
            renderStickyIcon(guiGraphics, playerIcon, playerData.currentPos());
        }
        if (playerData.spawnPos() != null) {
            renderStickyIcon(guiGraphics, spawnIcon, playerData.spawnPos());
        }
    }

    /**
     * Render the player and spawn icons in double the size
     */
    private void renderStickyIcon(GuiGraphics guiGraphics, PreviewIconTextureCache.IconData iconData, BlockPos pos) {
        TextureCoordinate texCenter = blockToTexture(pos);
        PreviewOverlayRenderer.renderStickyIcon(
                guiGraphics,
                iconData.textureId(),
                iconData.width(),
                iconData.height(),
                texCenter.x(),
                texCenter.z(),
                texWidth,
                texHeight,
                getX(),
                getY(),
                minecraft.getWindow().getGuiScale()
        );
    }

    private void biomesChanged() {
        final boolean biomesUnchanged = visibleCountsEqual(visibleBiomes, workingVisibleBiomes);
        final boolean structuresUnchanged = visibleCountsEqual(visibleStructures, workingVisibleStructures);
        if (biomesUnchanged && structuresUnchanged) {
            return;
        }

        Short2LongMap tempBiomesSet = new Short2LongOpenHashMap(workingVisibleBiomes.length);
        Short2LongMap tempStructuresSet = new Short2LongOpenHashMap(workingVisibleStructures.length);
        for (short i = 0; i < workingVisibleBiomes.length; ++i) {
            if (workingVisibleBiomes[i] > 0) {
                tempBiomesSet.put(i, workingVisibleBiomes[i]);
            }
        }
        for (short i = 0; i < workingVisibleStructures.length; ++i) {
            if (workingVisibleStructures[i] > 0) {
                tempStructuresSet.put(i, workingVisibleStructures[i]);
            }
        }

        if (!tempBiomesSet.equals(visibleBiomes)) {
            dataProvider.onVisibleBiomesChanged(tempBiomesSet);
        }
        if (!tempStructuresSet.equals(visibleStructures)) {
            dataProvider.onVisibleStructuresChanged(tempStructuresSet);
        }
        visibleBiomes = tempBiomesSet;
        visibleStructures = tempStructuresSet;
    }

    private static boolean visibleCountsEqual(Short2LongMap visible, long[] counts) {
        int nonZero = 0;
        for (long count : counts) {
            if (count > 0) {
                nonZero++;
            }
        }
        if (visible.size() != nonZero) {
            return false;
        }
        for (int i = 0; i < counts.length; ++i) {
            long count = counts[i];
            if (count > 0 && visible.get((short) i) != count) {
                return false;
            }
        }
        return true;
    }

    private HoverInfo hoveredBiome(double mouseX, double mouseY) {
        final PreviewStorage storage = workManager.previewStorage();
        if (!isHovered || storage == null) {
            return null;
        }
        int guiScale = minecraft.getWindow().getGuiScale();
        final double effectiveScale = effectiveScaleBlockPos();

        final BlockPos center = center();
        final int xMin = minBlockX(center);
        final int zMin = minBlockZ(center);

        final int xPos = (int) Math.floor((mouseX - getX()) * guiScale * effectiveScale);
        final int zPos = (int) Math.floor((mouseY - getY()) * guiScale * effectiveScale);

        int quartX = QuartPos.fromBlock(xMin + xPos);
        int quartY = QuartPos.fromBlock(center.getY());
        int quartZ = QuartPos.fromBlock(zMin + zPos);
        short biome = storage.getRawData4(quartX, quartY, quartZ, PreviewStorage.FLAG_BIOME);
        short height = storage.getRawData4(quartX, 0, quartZ, PreviewStorage.FLAG_HEIGHT);

        if (biome < 0) {
            return new HoverInfo(
                    xMin + xPos, center.getY(), zMin + zPos, null, height,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN
            );
        }

        if (!config.storeNoiseSamples) {
            return new HoverInfo(
                    xMin + xPos, center.getY(), zMin + zPos, dataProvider.biome4Id(biome), height,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN
            );
        }

        final short temperature = storage.getRawData4(quartX, quartY, quartZ, PreviewStorage.FLAG_NOISE_TEMPERATURE);
        final short humidity = storage.getRawData4(quartX, quartY, quartZ, PreviewStorage.FLAG_NOISE_HUMIDITY);
        final short continentalness = storage.getRawData4(quartX, quartY, quartZ, PreviewStorage.FLAG_NOISE_CONTINENTALNESS);
        final short erosion = storage.getRawData4(quartX, quartY, quartZ, PreviewStorage.FLAG_NOISE_EROSION);
        final short depth = storage.getRawData4(quartX, quartY, quartZ, PreviewStorage.FLAG_NOISE_DEPTH);
        final short weirdness = storage.getRawData4(quartX, quartY, quartZ, PreviewStorage.FLAG_NOISE_WEIRDNESS);

        if (temperature == Short.MIN_VALUE && humidity == Short.MIN_VALUE && continentalness == Short.MIN_VALUE && erosion == Short.MIN_VALUE && depth == Short.MIN_VALUE && weirdness == Short.MIN_VALUE) {
            return new HoverInfo(
                    xMin + xPos, center.getY(), zMin + zPos, dataProvider.biome4Id(biome), height,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN
            );      
        } else {
            return new HoverInfo(
                    xMin + xPos, center.getY(), zMin + zPos, dataProvider.biome4Id(biome), height,
                    temperature / 1.0 / Short.MAX_VALUE,
                    humidity / 1.0 / Short.MAX_VALUE,
                    continentalness / 0.5 / Short.MAX_VALUE,
                    erosion / 1.0 / Short.MAX_VALUE,
                    depth / 0.5 / Short.MAX_VALUE,
                    weirdness / 0.75 / Short.MAX_VALUE,
                    NoiseRouterData.peaksAndValleys(Math.clamp(weirdness / 0.75f / Short.MAX_VALUE, -1.0f, 1.0f))
            );
        }
    }

    private List<StructHoverHelperEntry> hoveredStructures(double mouseX, double mouseY) {
        if (!isHovered) {
            return List.of();
        }

        int guiScale = minecraft.getWindow().getGuiScale();
        final int xTexPos = (int) (mouseX - getX()) * guiScale;
        final int zTexPos = (int) (mouseY - getY()) * guiScale;

        final int xGridPos = xTexPos / hoverHelperGridCellSize;
        final int zGridPos = zTexPos / hoverHelperGridCellSize;

        final List<StructHoverHelperEntry> res = new ArrayList<>();
        for (int x = xGridPos - 1; x <= xGridPos + 1; ++x) {
            for (int z = zGridPos - 1; z <= zGridPos + 1; ++z) {
                if (x < 0 || x >= hoverHelperGridWidth || z < 0 || z >= hoverHelperGridHeight) {
                    continue;
                }
                StructHoverHelperCell cell = hoverHelperGrid[(x * hoverHelperGridHeight) + z];
                for (var entry : cell.entries) {
                    if (entry.boundingBox.isInside(xTexPos, 0, zTexPos)) {
                        res.add(entry);
                    }
                }
            }
        }
        return res;
    }

    private static String nameFormatter(String s) {
        return PreviewTooltipController.nameFormatter(s);
    }

    private void setTooltipNow(GuiGraphics guiGraphics, Tooltip tooltip, double mouseX, double mouseY) {
        PreviewTooltipController.setTooltipNow(minecraft, guiGraphics, tooltip, mouseX, mouseY);
    }

    private void updateTooltip(GuiGraphics guiGraphics, double mouseX, double mouseY) {
        HoverInfo hoverInfo = hoveredBiome(mouseX, mouseY);
        List<StructHoverHelperEntry> structuresInfos = hoveredStructures(mouseX, mouseY);
        if (hoverInfo == null && structuresInfos.isEmpty()) {
            return;
        }

        String blockPosTemplate = "§3X=§b%d§r §3Y=§b%d§r §3Z=§b%d§r";


        if (!structuresInfos.isEmpty()) {
            var structure = structuresInfos.getFirst().structure;
            if (config.showControls) {
                setTooltipNow(guiGraphics, Tooltip.create(Component.translatable(
                        "world_preview.preview-display.struct.tooltip.controls",
                        nameFormatter(dataProvider.structure4Id(structure.structureId()).name()),
                        blockPosTemplate.formatted(structure.center().getX(), structure.center().getY(), structure.center().getZ())
                )), mouseX, mouseY);
            } else {
                setTooltipNow(guiGraphics, Tooltip.create(Component.translatable(
                        "world_preview.preview-display.struct.tooltip",
                        nameFormatter(dataProvider.structure4Id(structure.structureId()).name()),
                        blockPosTemplate.formatted(structure.center().getX(), structure.center().getY(), structure.center().getZ())
                )), mouseX, mouseY);
            }
            return;
        }

        String height = hoverInfo.height > Short.MIN_VALUE ? String.format("§b%d§r", hoverInfo.height) : "§7<N/A>§r";
        String noise = "";
        if (!Double.isNaN(hoverInfo.temperature)) {
            noise = "\n\n§3T=§b%.2f§r §3H=§b%.2f§r §3C=§b%.2f§r\n§3E=§b%.2f§r §3D=§b%.2f§r §3W=§b%.2f§r\n§3PV=§b%.2f§r".formatted(
                    hoverInfo.temperature,
                    hoverInfo.humidity,
                    hoverInfo.continentalness,
                    hoverInfo.erosion,
                    hoverInfo.depth,
                    hoverInfo.weirdness,
                    hoverInfo.pv
            );
        }

        if (config.showControls) {
            setTooltipNow(guiGraphics, Tooltip.create(Component.translatable(
                    "world_preview.preview-display.tooltip.controls",
                    nameFormatter(hoverInfo.entry == null ? "<N/A>" : hoverInfo.entry.name()),
                    blockPosTemplate.formatted(hoverInfo.blockX, hoverInfo.blockY, hoverInfo.blockZ),
                    height,
                    noise
            )), mouseX, mouseY);
        } else {
            setTooltipNow(guiGraphics, Tooltip.create(Component.translatable(
                    "world_preview.preview-display.tooltip",
                    nameFormatter(hoverInfo.entry == null ? "<N/A>" : hoverInfo.entry.name()),
                    blockPosTemplate.formatted(hoverInfo.blockX, hoverInfo.blockY, hoverInfo.blockZ),
                    height,
                    noise
                    )), mouseX, mouseY);
        }
    }

    @Override
    public void playDownSound(@NotNull SoundManager handler) {
        // By default, do nothing
    }

    @Override
    public void onClick(@NotNull MouseButtonEvent event, boolean isDoubleClick) {
        if (minecraft.screen != null) {
            minecraft.screen.setFocused(this);
        }

        /*
         * We clicked into the canvas, save this to make sure a mouse release did not come from outside the preview.
         * Note: This causes a problem if the mouse is released outside of the preview,
         * requiring a double click to highlight a biome
         */
        clicked = true;
    }

    @Override
    protected void onDrag(@NotNull MouseButtonEvent event, double mouseX, double mouseY) {
        final double guiScale = minecraft.getWindow().getGuiScale();
        final double effectiveScale = effectiveScaleBlockPos();
        final double dragDeltaX = -(mouseX * guiScale) * effectiveScale;
        final double dragDeltaZ = -(mouseY * guiScale) * effectiveScale;
        totalDragX += dragDeltaX;
        totalDragZ += dragDeltaZ;

        final long nowMs = System.currentTimeMillis();
        if (lastDragSampleMs > 0L) {
            final long dtMs = Math.max(1L, nowMs - lastDragSampleMs);
            final double factor = 1000.0 / dtMs;
            final double instantVX = dragDeltaX * factor;
            final double instantVZ = dragDeltaZ * factor;
            dragVelocityBlocksX += (instantVX - dragVelocityBlocksX) * DRAG_VELOCITY_SMOOTHING;
            dragVelocityBlocksZ += (instantVZ - dragVelocityBlocksZ) * DRAG_VELOCITY_SMOOTHING;
        } else {
            dragVelocityBlocksX = 0.0;
            dragVelocityBlocksZ = 0.0;
        }
        lastDragSampleMs = nowMs;
    }

    @Override
    public void onRelease(@NotNull MouseButtonEvent event) {

        // If we did not click into the canvas at the start, then we ignore this release
        if (!clicked) {
            return;
        }
        clicked = false;

        final boolean dragged = Math.abs(totalDragX) > DRAG_CLICK_THRESHOLD_BLOCKS || Math.abs(totalDragZ) > DRAG_CLICK_THRESHOLD_BLOCKS;

        // Check if dragged was minimal
        if (!dragged) {
            HoverInfo hoverInfo = hoveredBiome(event.x(), event.y());
            if (hoverInfo == null || hoverInfo.entry == null) {
                return;
            }

            super.playDownSound(minecraft.getSoundManager());
            if (selectedBiomeId == hoverInfo.entry.id()) {
                dataProvider.onBiomeVisuallySelected(null);
            } else {
                dataProvider.onBiomeVisuallySelected(hoverInfo.entry);
            }
        }

        // Finalize drag
        renderSettings.setCenter(center());

        totalDragX = 0;
        totalDragZ = 0;
        dragVelocityBlocksX = 0.0;
        dragVelocityBlocksZ = 0.0;
        lastDragSampleMs = 0L;

        if (dragged) {
            // Restart pass immediately at the finalized center to avoid visible black regions lingering after fast drags.
            textureRenderInProgress = false;
            textureRenderSectionCursor = 0;
            textureRenderXCursor = Integer.MIN_VALUE;
            texturePassState = null;
            dragBurstUntilMs = System.currentTimeMillis() + DRAG_RENDER_BURST_DURATION_MS;

            // Queue generation immediately instead of waiting for periodic refresh.
            queueGeneration();
            lastQueuedState = currentRenderState();
            lastQueueRefreshMs = System.currentTimeMillis();
            lastTextureRefreshMs = 0L;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        synchronized (dataProvider) {
            if (dataProvider.isUpdating()) {
                return true;
            }
            final double scrollDelta = deltaX + deltaY;
            if (scrollDelta == 0.0) {
                return true;
            }
            if (InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)) {
                final double nextZoomFactor = zoomFactor * Math.pow(ZOOM_SCROLL_FACTOR, scrollDelta);
                zoomFactor = Math.clamp(nextZoomFactor, MIN_ZOOM_FACTOR, MAX_ZOOM_FACTOR);
            } else if (scrollDelta > 0.0) {
                renderSettings.decrementY();
            } else {
                renderSettings.incrementY();
            }
            return true;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        if (this.isMouseOver(event.x(), event.y()) && event.button() == 1) {
            this.playDownSound(minecraft.getSoundManager());

            final HoverInfo hoverInfo = hoveredBiome(event.x(), event.y());
            if (hoverInfo == null) {
                return true;
            }
            final String coordinates = String.format(
                    "%s %s %s",
                    hoverInfo.blockX,
                    hoverInfo.height == Short.MIN_VALUE ? "~" : hoverInfo.height,
                    hoverInfo.blockZ
            );

            minecraft.keyboardHandler.setClipboard(coordinates);
            coordinatesCopiedAtMs = System.currentTimeMillis();
            coordinatesCopiedMsg = Component.translatable("world_preview.preview-display.coordinates.copied", coordinates);
            return true;
        }

        return super.mouseClicked(event, isDoubleClick);
    }

    private static int textureColor(int orig) {
        final int R = (orig >> 16) & 0xFF;
        final int G = (orig >> 8) & 0xFF;
        final int B = orig & 0xFF;
        return (R << 16) | (G << 8) | B | (0xFF << 24);
    }

    private static int highlightColor(int orig) {
        int R = (orig >> 16) & 0xFF;
        int G = (orig >> 8) & 0xFF;
        int B = orig & 0xFF;

        final int diff = ((R + G + B) / 3) > 200 ? -100 : 100;

        R += diff;
        G += diff;
        B += diff;
        R = Math.clamp(R, 0, 255);
        G = Math.clamp(G, 0, 255);
        B = Math.clamp(B, 0, 255);
        return (0xFF << 24) | (R << 16) | (G << 8) | B;
    }

    private static int grayScale(int orig) {
        int R = (orig >> 16) & 0xFF;
        int G = (orig >> 8) & 0xFF;
        int B = orig & 0xFF;

        final int gray = Math.clamp((R + G + B) / 3, 32, 256 - 32);
        return (0xFF << 24) | (gray << 16) | (gray << 8) | gray;
    }

    /**
     * Negative values for none
     */
    public void setSelectedBiomeId(short biomeId) {
        selectedBiomeId = biomeId;
    }

    public void setHighlightCaves(boolean highlightCaves) {
        this.highlightCaves = highlightCaves;
    }

    @Override
    protected void updateWidgetNarration(@NotNull NarrationElementOutput narrationElementOutput) {
        // Nothing to do
    }

    private record HoverInfo(
            int blockX,
            int blockY,
            int blockZ,
            BiomesList.BiomeEntry entry,
            short height,
            double temperature,
            double humidity,
            double continentalness,
            double erosion,
            double depth,
            double weirdness,
            double pv
    ) {}

    private record StructHoverHelperCell(List<StructHoverHelperEntry> entries) {
    }

    private record StructHoverHelperEntry(BoundingBox boundingBox, PreviewSection.PreviewStruct structure) {
    }
}
