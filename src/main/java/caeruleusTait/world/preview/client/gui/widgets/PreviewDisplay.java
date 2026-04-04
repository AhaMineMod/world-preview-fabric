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
import org.lwjgl.glfw.GLFW;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Queue;

import static caeruleusTait.world.preview.client.WorldPreviewComponents.MSG_ERROR_SETUP_FAILED;
import static caeruleusTait.world.preview.client.WorldPreviewComponents.MSG_PREVIEW_SETUP_LOADING;

public class PreviewDisplay extends AbstractWidget implements AutoCloseable {
    private static final double MIN_ZOOM_FACTOR = 0.25;
    private static final double MAX_ZOOM_FACTOR = 16.0;
    private static final double ZOOM_SCROLL_FACTOR = 1.1;
    private static final int STRUCTURE_ICON_TARGET_SIZE = 16;

    private final Minecraft minecraft;
    private final PreviewDisplayDataProvider dataProvider;
    private final WorkManager workManager;
    private final RenderSettings renderSettings;
    private final WorldPreviewConfig config;
    private Short2LongMap visibleBiomes;
    private Short2LongMap visibleStructures;
    private NativeImage previewImg;
    private DynamicTexture previewTexture;
    private Identifier previewTextureId;
    private long[] workingVisibleBiomes;
    private long[] workingVisibleStructures;
    private int[] colorMap;
    private int[] colorMapGrayScale;
    private int[] heightColorMap;
    private int[] noiseColorMap;
    private boolean[] cavesMap;
    private IconData[] structureIcons;
    private IconData playerIcon;
    private IconData spawnIcon;
    private ItemStack[] structureItems;
    private PreviewDisplayDataProvider.StructureRenderInfo[] structureRenderInfoMap;
    private final NativeImage dummyIcon;

    private Component coordinatesCopiedMsg = null;
    private Instant coordinatesCopiedTime = null;

    private int texWidth = 100;
    private int texHeight = 100;

    private short selectedBiomeId;
    private boolean highlightCaves;

    private double totalDragX = 0;
    private double totalDragZ = 0;

    private int scaleBlockPos = 1;
    private double zoomFactor = 1.0;

    private StructHoverHelperCell[] hoverHelperGrid;
    private final int hoverHelperGridCellSize = 64;
    private int hoverHelperGridWidth;
    private int hoverHelperGridHeight;

    private Queue<Long> frametimes = new ArrayDeque<>();

    private boolean clicked = false;

    private record IconData(int width, int height, @NotNull DynamicTexture texture, @NotNull Identifier textureId) {
        public void close(Minecraft minecraft) {
            minecraft.getTextureManager().release(textureId);
        }
    }

    public PreviewDisplay(Minecraft minecraft, PreviewDisplayDataProvider dataProvider, Component component) {
        super(0, 0, 100, 100, component);
        this.minecraft = minecraft;
        this.workManager = WorldPreview.get().workManager();
        this.dataProvider = dataProvider;
        this.visibleBiomes = new Short2LongOpenHashMap();
        this.visibleStructures = new Short2LongOpenHashMap();
        this.renderSettings = WorldPreview.get().renderSettings();
        this.config = WorldPreview.get().cfg();
        this.dummyIcon = new NativeImage(16, 16, true);
        this.structureIcons = new IconData[0];
        resizeImage();
    }

    public void resizeImage() {
        closeDisplayTextures();
        previewImg = new NativeImage(NativeImage.Format.RGBA, texWidth, texHeight, true);
        previewTexture = new DynamicTexture(() -> "world_preview:preview_display", previewImg);
        previewTextureId = Identifier.tryBuild("world_preview", "dynamic/preview_display");
        minecraft.getTextureManager().register(previewTextureId, previewTexture);
        scaleBlockPos = (QuartPos.SIZE / renderSettings.quartExpand()) * renderSettings.quartStride();
        hoverHelperGridWidth = (texWidth / hoverHelperGridCellSize) + 1;
        hoverHelperGridHeight = (texHeight / hoverHelperGridCellSize) + 1;
        hoverHelperGrid = new StructHoverHelperCell[hoverHelperGridWidth * hoverHelperGridHeight];
        for (int i = 0; i < hoverHelperGrid.length; ++i) {
            hoverHelperGrid[i] = new StructHoverHelperCell(new ArrayList<>());
        }
    }

    public void setSize(int width, int height) {
        this.width = width;
        this.height = height;
        this.texWidth = this.width * (int) minecraft.getWindow().getGuiScale();
        this.texHeight = this.height * (int) minecraft.getWindow().getGuiScale();
        resizeImage();
    }

    public void reloadData() {
        // Cleanup previous
        closeIconTextures();

        PreviewData.BiomeData[] rawBiomeMap = dataProvider.previewData().biomeId2BiomeData();
        structureRenderInfoMap = dataProvider.renderStructureMap();
        structureItems = dataProvider.structureItems();
        NativeImage[] rawStructureIcons = dataProvider.structureIcons();
        structureIcons = new IconData[rawStructureIcons.length];
        for (int i = 0; i < rawStructureIcons.length; ++i) {
            final int iconIndex = i;
            NativeImage icon = rawStructureIcons[i];
            DynamicTexture texture = new DynamicTexture(() -> "world_preview:structure_icon_" + iconIndex, icon);
            Identifier textureId = Identifier.tryBuild("world_preview", "dynamic/preview_display/structure_" + i);
            minecraft.getTextureManager().register(textureId, texture);
            structureIcons[i] = new IconData(icon.getWidth(), icon.getHeight(), texture, textureId);
        }
        NativeImage rawPlayerIcon = dataProvider.playerIcon();
        DynamicTexture rawPlayerTexture = new DynamicTexture(() -> "world_preview:player_icon", rawPlayerIcon);
        Identifier playerTextureId = Identifier.tryBuild("world_preview", "dynamic/preview_display/player");
        minecraft.getTextureManager().register(playerTextureId, rawPlayerTexture);
        playerIcon = new IconData(rawPlayerIcon.getWidth(), rawPlayerIcon.getHeight(), rawPlayerTexture, playerTextureId);

        NativeImage rawSpawnIcon = dataProvider.spawnIcon();
        DynamicTexture rawSpawnTexture = new DynamicTexture(() -> "world_preview:spawn_icon", rawSpawnIcon);
        Identifier spawnTextureId = Identifier.tryBuild("world_preview", "dynamic/preview_display/spawn");
        minecraft.getTextureManager().register(spawnTextureId, rawSpawnTexture);
        spawnIcon = new IconData(rawSpawnIcon.getWidth(), rawSpawnIcon.getHeight(), rawSpawnTexture, spawnTextureId);
        try {
            heightColorMap = dataProvider.heightColorMap();
            noiseColorMap = dataProvider.noiseColorMap();
        } catch (Throwable e) {
            e.printStackTrace();
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
    }

    private void closeIconTextures() {
        if (structureIcons != null) {
            Arrays.stream(structureIcons).forEach(x -> x.close(minecraft));
        }
        if (playerIcon != null) {
            playerIcon.close(minecraft);
        }
        if (spawnIcon != null) {
            spawnIcon.close(minecraft);
        }
        playerIcon = null;
        spawnIcon = null;
    }

    private void closeDisplayTextures() {
        if (previewTextureId != null) {
            minecraft.getTextureManager().release(previewTextureId);
            previewTextureId = null;
            previewTexture = null;
            previewImg = null;
        }
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

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int x, int y, float f) {
        final int colorBorder = 0xFF666666;

        final int xMin = getX();
        final int yMin = getY();
        final int xMax = xMin + width;
        final int yMax = yMin + height;

        final Instant renderStart = Instant.now();
        queueGeneration();
        synchronized (dataProvider) {
            if (dataProvider.setupFailed()) {
                previewImg.fillRect(0, 0, texWidth, texHeight, 0xFF000000);
                previewTexture.upload();
                WorldPreviewClient.renderTexture(guiGraphics, previewTextureId, xMin, yMin, xMax, yMax);

                final List<MutableComponent> lines = MSG_ERROR_SETUP_FAILED.getString().lines().map(Component::literal).toList();

                final int centerX = getX() + (width / 2);
                final int centerY = getY() + (height / 2) - ((lines.size() / 2) * (minecraft.font.lineHeight + 4));

                for (int i = 0; i < lines.size(); ++i) {
                    final Component line = lines.get(i);
                    final int offsetY = i * (minecraft.font.lineHeight + 4);
                    guiGraphics.drawCenteredString(minecraft.font, line, centerX, centerY + offsetY, 0xFFFFFFFF);
                }
            } else if (dataProvider.isUpdating()) {
                previewImg.fillRect(0, 0, texWidth, texHeight, 0xFF000000);
                previewTexture.upload();
                WorldPreviewClient.renderTexture(guiGraphics, previewTextureId, xMin, yMin, xMax, yMax);

                final int centerX = getX() + (width / 2);
                final int centerY = getY() + (height / 2);
                guiGraphics.drawCenteredString(minecraft.font, MSG_PREVIEW_SETUP_LOADING, centerX, centerY, 0xFFFFFFFF);
            } else {
                Arrays.fill(workingVisibleBiomes, (short) 0);
                Arrays.fill(workingVisibleStructures, (short) 0);
                Arrays.stream(hoverHelperGrid).forEach(cell -> cell.entries.clear());
                final List<RenderHelper> renderData = generateRenderData();
                updateTexture(renderData);

                previewTexture.upload();

                // Render the main texture
                WorldPreviewClient.renderTexture(guiGraphics, previewTextureId, xMin, yMin, xMax, yMax);

                // Overlay structure icons
                guiGraphics.enableScissor(xMin, yMin, xMax, yMax);
                renderStructures(renderData, guiGraphics);
                renderPlayerAndSpawn(guiGraphics);
                guiGraphics.disableScissor();

                // Update hover info
                double mouseX = (minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth()) / minecraft.getWindow()
                        .getScreenWidth();
                double mouseZ = (minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight()) / minecraft.getWindow()
                        .getScreenHeight();

                biomesChanged();
                updateTooltip(guiGraphics, mouseX, mouseZ);
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
            if (Duration.between(coordinatesCopiedTime, Instant.now()).toSeconds() >= 8) {
                coordinatesCopiedMsg = null;
                coordinatesCopiedTime = null;
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

        final Instant renderEnd = Instant.now();
        frametimes.add(Duration.between(renderStart, renderEnd).abs().toMillis());
        while (frametimes.size() > 30) {
            frametimes.poll();
        }
        long sum = frametimes.stream().reduce(0L, Long::sum);

        if (config.showFrameTime) {
            guiGraphics.drawString(minecraft.font, sum / frametimes.size() + " ms", 5, 5, 0xFFFFFFFF);
        }
    }

    private record TextureCoordinate(int x, int z) {}

    private String formatZoomLabel() {
        final double roundedZoom = Math.round(zoomFactor * 100.0) / 100.0;
        if (Math.abs(roundedZoom - Math.rint(roundedZoom)) < 0.0001) {
            return String.format(Locale.ROOT, "%.0fx", roundedZoom);
        }
        if (Math.abs((roundedZoom * 10.0) - Math.rint(roundedZoom * 10.0)) < 0.0001) {
            return String.format(Locale.ROOT, "%.1fx", roundedZoom);
        }
        return String.format(Locale.ROOT, "%.2fx", roundedZoom);
    }

    private double effectiveScaleBlockPos() {
        return scaleBlockPos / zoomFactor;
    }

    private int minBlockX(BlockPos center) {
        return (int) Math.floor(center.getX() - (texWidth * effectiveScaleBlockPos() / 2.0) - 1.0);
    }

    private int maxBlockX(BlockPos center) {
        return (int) Math.ceil(center.getX() + (texWidth * effectiveScaleBlockPos() / 2.0) + 1.0);
    }

    private int minBlockZ(BlockPos center) {
        return (int) Math.floor(center.getZ() - (texHeight * effectiveScaleBlockPos() / 2.0) - 1.0);
    }

    private int maxBlockZ(BlockPos center) {
        return (int) Math.ceil(center.getZ() + (texHeight * effectiveScaleBlockPos() / 2.0) + 1.0);
    }

    private TextureCoordinate blockToTexture(BlockPos blockPos) {
        BlockPos center = center();
        final int xMin = minBlockX(center);
        final int zMin = minBlockZ(center);
        final double effectiveScale = effectiveScaleBlockPos();

        return new TextureCoordinate(
                (int) Math.floor((blockPos.getX() - xMin) / effectiveScale),
                (int) Math.floor((blockPos.getZ() - zMin) / effectiveScale)
        );
    }

    private void putHoverStructEntry(TextureCoordinate pos, StructHoverHelperEntry entry) {
        int cellX = Math.max(0, Math.min(hoverHelperGridWidth - 1, pos.x / hoverHelperGridCellSize));
        int cellZ = Math.max(0, Math.min(hoverHelperGridHeight - 1, pos.z / hoverHelperGridCellSize));
        hoverHelperGrid[(cellX * hoverHelperGridHeight) + cellZ].entries.add(entry);
    }

    private void queueGeneration() {
        final BlockPos center = center();
        final int xMin = minBlockX(center);
        final int xMax = maxBlockX(center);
        final int zMin = minBlockZ(center);
        final int zMax = maxBlockZ(center);
        final int y = center.getY();
        workManager.queueRange(new BlockPos(xMin, y, zMin), new BlockPos(xMax, y, zMax));
    }

    private record RenderHelper(
            PreviewSection dataSection,
            PreviewSection structureSection,
            PreviewSection.AccessData accessData
    ) {
    }

    private List<RenderHelper> generateRenderData() {
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

        PreviewStorage storage = workManager.previewStorage();

        // Load sections
        synchronized (storage) {
            while (true) {
                long flag = renderSettings.mode.flag;
                int useY = renderSettings.mode.useY ? quartY : 0;
                PreviewSection dataSection = storage.section4(quartX, useY, quartZ, flag);
                PreviewSection structureSection = storage.section4(quartX, 0, quartZ, PreviewStorage.FLAG_STRUCT_START);
                PreviewSection.AccessData accessData = dataSection.calcQuartOffsetData(quartX, quartZ, maxQuartX, maxQuartZ);

                res.add(new RenderHelper(dataSection, structureSection, accessData));

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
        }

        return res;
    }

    private void updateTexture(List<RenderHelper> renderData) {
        final int quartStride = renderSettings.quartStride();
        final int blockStride = quartStride * QuartPos.SIZE;
        final BlockPos center = center();
        final int xMin = minBlockX(center);
        final int zMin = minBlockZ(center);
        final double effectiveScale = effectiveScaleBlockPos();
        previewImg.fillRect(0, 0, texWidth, texHeight, 0xFF000000);

        // Render the biomes / heightmap
        for (RenderHelper r : renderData) {
            // Draw all the relevant data in the section
            for(int x = r.accessData.minX(); x < r.accessData.maxX(); x += quartStride) {
                final int blockStartX = QuartPos.toBlock(r.dataSection.quartX() + x);
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
                    final int blockStartZ = QuartPos.toBlock(r.dataSection.quartZ() + z);
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

                    // Read the biome data
                    short rawData = r.dataSection.get(x, z);
                    int color = 0xFF000000;
                    switch (renderSettings.mode) {
                        case BIOMES -> {
                            if (rawData >= 0) {
                                color = selectedBiomeId >= 0 || highlightCaves ? colorMapGrayScale[rawData] : colorMap[rawData];
                                if (selectedBiomeId == rawData || (highlightCaves && cavesMap[rawData])) {
                                    color = colorMap[rawData];
                                }
                                workingVisibleBiomes[rawData] += 1;
                            }
                        }
                        case HEIGHTMAP -> {
                            if (rawData > Short.MIN_VALUE) {
                                color = heightColorMap[rawData - dataProvider.yMin()];
                            }
                        }
                        case INTERSECTIONS -> {
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
                            if (rawData > Short.MIN_VALUE) {
                                final float data = ((float) rawData) / ((float) Short.MAX_VALUE);
                                final int idx = Math.min(1023, Math.max(0, 512 + (int) (data * 512)));
                                color = noiseColorMap[idx];
                            }
                        }
                        case NOISE_PEAKS_AND_VALLEYS -> {
                            if (rawData > Short.MIN_VALUE) {
                                final float data = ((float) rawData) / 0.75f / ((float) Short.MAX_VALUE);
                                final float pvData = NoiseRouterData.peaksAndValleys(Math.min(1.0f, Math.max(-1.0f, data)));
                                final int idx = Math.min(1023, Math.max(0, 512 + (int) (pvData * 512)));
                                color = noiseColorMap[idx];
                            }
                        }
                    }

                    previewImg.fillRect(texXMin, texZMin, texXSize, texZSize, color);
                }
            }


        }
    }

    private void renderStructures(List<RenderHelper> renderData, GuiGraphics guiGraphics) {
        if (!config.sampleStructures) {
            return;
        }

        final double guiScale = minecraft.getWindow().getGuiScale();

        // Draw structures
        //  - Do this in a separate RenderHelper loop to ensure that the biome data is overwritten
        for (RenderHelper r : renderData) {
            for (PreviewSection.PreviewStruct structure : r.structureSection.structures()) {
                short id = structure.structureId();
                TextureCoordinate texCenter = blockToTexture(structure.center());
                IconData iconData = structureIcons[id];
                Identifier iconTexture = iconData != null ? iconData.textureId() : null;
                ItemStack item = structureItems[id];
                if (iconTexture == null && item == null) {
                    continue;
                }

                final int iconWidth;
                final int iconHeight;
                if (item != null) {
                    iconWidth = STRUCTURE_ICON_TARGET_SIZE;
                    iconHeight = STRUCTURE_ICON_TARGET_SIZE;
                } else {
                    final int rawIconWidth = iconData != null ? iconData.width() : dummyIcon.getWidth();
                    final int rawIconHeight = iconData != null ? iconData.height() : dummyIcon.getHeight();
                    final double iconScale = STRUCTURE_ICON_TARGET_SIZE / (double) Math.max(rawIconWidth, rawIconHeight);
                    iconWidth = Math.max(1, (int) Math.round(rawIconWidth * iconScale));
                    iconHeight = Math.max(1, (int) Math.round(rawIconHeight * iconScale));
                }

                // Check if visible
                final int xMin = -(iconWidth / 2);
                final int xMax = (iconWidth / 2) + 1 + texWidth;
                final int zMin = -(iconHeight / 2);
                final int zMax = (iconHeight / 2) + 1 + texHeight;
                if (texCenter.x < xMin || texCenter.z < zMin || texCenter.x > xMax || texCenter.z > zMax) {
                    continue;
                }

                workingVisibleStructures[id] += 1;

                // Do not render hidden structures, but still count them
                if (!structureRenderInfoMap[id].show() || renderSettings.hideAllStructures) {
                    continue;
                }

                // Render icon / item
                final int texStartX = texCenter.x - (iconWidth / 2);
                final int texStartZ = texCenter.z - (iconHeight / 2);

                final int rXMin = getX() + (int) Math.round(texStartX / guiScale);
                final int rZMin = getY() + (int) Math.round(texStartZ / guiScale);
                final int renderWidth = Math.max(1, (int) Math.round(iconWidth / guiScale));
                final int renderHeight = Math.max(1, (int) Math.round(iconHeight / guiScale));
                final int rXMax = rXMin + renderWidth;
                final int rZMax = rZMin + renderHeight;

                if (item != null) {
                    guiGraphics.pose().pushMatrix();
                    guiGraphics.pose().translate(rXMin, rZMin);
                    guiGraphics.pose().scale(renderWidth / 16f, renderHeight / 16f);
                    guiGraphics.renderItem(item, 0, 0);
                    guiGraphics.pose().popMatrix();
                } else if (iconTexture != null) {
                    WorldPreviewClient.renderTexture(guiGraphics, iconTexture, rXMin, rZMin, rXMax, rZMax);
                }

                putHoverStructEntry(
                        texCenter,
                        new StructHoverHelperEntry(
                                new BoundingBox(texStartX, 0, texStartZ, texStartX + iconWidth, 0, texStartZ + iconHeight),
                                structure
                        )
                );
            }
        }
    }

    private void renderPlayerAndSpawn(GuiGraphics guiGraphics) {
        if (!config.showPlayer) {
            return;
        }

        PreviewDisplayDataProvider.PlayerData playerData = dataProvider.getPlayerData(minecraft.getUser().getProfileId());
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
    private void renderStickyIcon(GuiGraphics guiGraphics, IconData iconData, BlockPos pos) {
        final double guiScale = minecraft.getWindow().getGuiScale();
        final int iconWidth = iconData.width();
        final int iconHeight = iconData.height();

        TextureCoordinate texCenter = blockToTexture(pos);
        texCenter = new TextureCoordinate(
                Math.max(0, Math.min(texWidth, texCenter.x)),
                Math.max(0, Math.min(texHeight, texCenter.z))
        );

        // Render icon / item
        final int texStartX = texCenter.x - iconWidth;
        final int texStartZ = texCenter.z - iconHeight;

        final int rXMin = getX() + (int) Math.round(texStartX / guiScale);
        final int rZMin = getY() + (int) Math.round(texStartZ / guiScale);
        final int rXMax = rXMin + Math.max(1, (int) Math.round((iconWidth * 2) / guiScale));
        final int rZMax = rZMin + Math.max(1, (int) Math.round((iconHeight * 2) / guiScale));

        WorldPreviewClient.renderTexture(guiGraphics, iconData.textureId(), rXMin, rZMin, rXMax, rZMax);
    }

    private void biomesChanged() {
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

    private HoverInfo hoveredBiome(double mouseX, double mouseY) {
        if (!isHovered || workManager.previewStorage() == null) {
            return null;
        }
        int guiScale = (int) minecraft.getWindow().getGuiScale();
        final double effectiveScale = effectiveScaleBlockPos();

        final BlockPos center = center();
        final int xMin = minBlockX(center);
        final int zMin = minBlockZ(center);

        final int xPos = (int) Math.floor((mouseX - getX()) * guiScale * effectiveScale);
        final int zPos = (int) Math.floor((mouseY - getY()) * guiScale * effectiveScale);

        int quartX = QuartPos.fromBlock(xMin + xPos);
        int quartY = QuartPos.fromBlock(center.getY());
        int quartZ = QuartPos.fromBlock(zMin + zPos);
        short biome = workManager.previewStorage().getRawData4(quartX, quartY, quartZ, PreviewStorage.FLAG_BIOME);
        short height = workManager.previewStorage().getRawData4(quartX, 0, quartZ, PreviewStorage.FLAG_HEIGHT);

        if (biome < 0) {
            return new HoverInfo(
                    xMin + xPos, center.getY(), zMin + zPos, null, height,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN
            );
        }

        final short temperature = workManager.previewStorage().getRawData4(quartX, quartY, quartZ, PreviewStorage.FLAG_NOISE_TEMPERATURE);
        final short humidity = workManager.previewStorage().getRawData4(quartX, quartY, quartZ, PreviewStorage.FLAG_NOISE_HUMIDITY);
        final short continentalness = workManager.previewStorage().getRawData4(quartX, quartY, quartZ, PreviewStorage.FLAG_NOISE_CONTINENTALNESS);
        final short erosion = workManager.previewStorage().getRawData4(quartX, quartY, quartZ, PreviewStorage.FLAG_NOISE_EROSION);
        final short depth = workManager.previewStorage().getRawData4(quartX, quartY, quartZ, PreviewStorage.FLAG_NOISE_DEPTH);
        final short weirdness = workManager.previewStorage().getRawData4(quartX, quartY, quartZ, PreviewStorage.FLAG_NOISE_WEIRDNESS);

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
                    NoiseRouterData.peaksAndValleys(Math.min(1.0f, Math.max(-1.0f, weirdness / 0.75f / Short.MAX_VALUE)))
            );
        }
    }

    private List<StructHoverHelperEntry> hoveredStructures(double mouseX, double mouseY) {
        if (!isHovered) {
            return List.of();
        }

        int guiScale = (int) minecraft.getWindow().getGuiScale();
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
        int idx = s.indexOf(':');
        if (idx < 0) {
            return "§e" + s + "§r";
        }
        return String.format("§5§o%s§r§5:%s§r", s.substring(0, idx), s.substring(idx + 1));
    }

    private void setTooltipNow(GuiGraphics guiGraphics, Tooltip tooltip, double mouseX, double mouseY) {
        guiGraphics.setTooltipForNextFrame(minecraft.font, tooltip.toCharSequence(minecraft), (int) mouseX, (int) mouseY);
    }

    private void updateTooltip(GuiGraphics guiGraphics, double mouseX, double mouseY) {
        HoverInfo hoverInfo = hoveredBiome(mouseX, mouseY);
        List<StructHoverHelperEntry> structuresInfos = hoveredStructures(mouseX, mouseY);
        if (hoverInfo == null && structuresInfos.isEmpty()) {
            return;
        }

        String blockPosTemplate = "§3X=§b%d§r §3Y=§b%d§r §3Z=§b%d§r";


        if (!structuresInfos.isEmpty()) {
            var structure = structuresInfos.get(0).structure;
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
    public void playDownSound(SoundManager handler) {
        // By default, do nothing
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean isDoubleClick) {
        if (minecraft.screen != null) {
            minecraft.screen.setFocused(this);
        }

        /**
         * We clicked into the canvas, save this to make sure a mouse release did not come from outside the preview.
         * Note: This causes a problem if the mouse is released outside of the preview,
         * requiring a double click to highlight a biome
         */
        clicked = true;
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double mouseX, double mouseY) {
        final double guiScale = minecraft.getWindow().getGuiScale();
        final double effectiveScale = effectiveScaleBlockPos();
        totalDragX -= (mouseX * guiScale) * effectiveScale;
        totalDragZ -= (mouseY * guiScale) * effectiveScale;
    }

    @Override
    public void onRelease(MouseButtonEvent event) {

        // If we did not click into the canvas at the start, then we ignore this release
        if (!clicked) {
            return;
        }
        clicked = false;

        // Check if dragged was minimal
        if (Math.abs(totalDragX) <= 4 && Math.abs(totalDragZ) <= 4) {
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
            coordinatesCopiedTime = Instant.now();
            coordinatesCopiedMsg = Component.translatable("world_preview.preview-display.coordinates.copied", coordinates);
            return true;
        }

        return super.mouseClicked(event, isDoubleClick);
    }

    private static int textureColor(int orig) {
        final int R = (orig >> 16) & 0xFF;
        final int G = (orig >> 8) & 0xFF;
        final int B = (orig >> 0) & 0xFF;
        return (R << 16) | (G << 8) | (B << 0) | (0xFF << 24);
    }

    private static int highlightColor(int orig) {
        int R = (orig >> 16) & 0xFF;
        int G = (orig >> 8) & 0xFF;
        int B = (orig >> 0) & 0xFF;

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
        int B = (orig >> 0) & 0xFF;

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
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
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
