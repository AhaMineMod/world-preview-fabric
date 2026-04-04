package caeruleusTait.world.preview.client.gui.widgets.lists;

import caeruleusTait.world.preview.client.WorldPreviewClient;
import caeruleusTait.world.preview.client.gui.widgets.ToggleButton;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;

import static caeruleusTait.world.preview.client.gui.screens.PreviewContainer.*;

public class StructuresList extends BaseObjectSelectionList<StructuresList.StructureEntry> {

    public StructuresList(Minecraft minecraft, int width, int height, int x, int y) {
        super(minecraft, width, height, x, y, 24);
    }

    public StructureEntry createEntry(short id, Identifier Identifier, NativeImage icon, Item item, String name, boolean show, boolean showByDefault) {
        return new StructureEntry(id, Identifier, icon, item, name, show, showByDefault);
    }

    @Override
    public void replaceEntries(Collection<StructureEntry> entryList) {
        super.replaceEntries(entryList);

        // If we have more than one page, make sure we don't let the scrollbar run away
        double maxScroll = Math.max(0.0, super.contentHeight() - super.height);
        if (super.scrollAmount() > maxScroll) {
            // Make sure that the top entry is visible
            super.setScrollAmount(maxScroll);
        }
    }

    public class StructureEntry extends BaseObjectSelectionList.Entry<StructuresList.StructureEntry> implements StructureRenderInfo {
        private final short id;
        private final NativeImage icon;
        private final Item item;
        private final ItemStack itemStack;
        private final DynamicTexture iconTexture;
        private final Identifier iconTextureId;
        private final int iconWidth;
        private final int iconHeight;
        private final String name;
        private final Tooltip tooltip;
        private final boolean showByDefault;
        private final boolean isPrimaryNamespace;

        private boolean show;
        public final ToggleButton toggleVisible;

        public StructureEntry(short id, Identifier Identifier, @NotNull NativeImage icon, @Nullable Item item, String name, boolean show, boolean showByDefault) {
            this.id = id;
            this.item = item;
            this.itemStack = this.item == null ? null : new ItemStack(this.item, 1);
            this.icon = icon;
            this.iconTexture = new DynamicTexture(() -> "world_preview:structure_list_" + id, this.icon);
            this.iconTextureId = Identifier.tryBuild("world_preview", "dynamic/structures_list/" + id);
            this.iconWidth = this.icon.getWidth();
            this.iconHeight = this.icon.getHeight();
            this.showByDefault = showByDefault;
            this.show = show;
            this.toggleVisible = new ToggleButton(
                    0, 0, 20, 20, /* x, y, width, height */
                    140, 20, 20, 20, /* xTexStart, yTexStart, xDiffTex, yDiffTex */
                    BUTTONS_TEXTURE, BUTTONS_TEX_WIDTH, BUTTONS_TEX_HEIGHT, /* Identifier, textureWidth, textureHeight*/
                    this::toggleVisible
            );

            minecraft.getTextureManager().register(this.iconTextureId, this.iconTexture);
            this.iconTexture.upload();
            this.toggleVisible.selected = show;

            this.isPrimaryNamespace = Identifier.getNamespace().equals("minecraft");
            final String modLangKey = "world_preview.structure." + Identifier.getNamespace() + "." + Identifier.getPath();
            final String vanillaLangKey = Identifier.toLanguageKey("structure");
            if (Language.getInstance().has(modLangKey)) {
                this.name = Component.translatable(modLangKey).getString();
            } else if (Language.getInstance().has(vanillaLangKey)) {
                this.name = Component.translatable(vanillaLangKey).getString();
            } else if (Objects.equals(Identifier.toString(), name) || name == null || name.isBlank()) {
                this.name = WorldPreviewClient.toTitleCase(Identifier.getPath().replace("_", " "));
            } else {
                this.name = name;
            }

            String tag = "§5§o" + Identifier.getNamespace() + "§r\n§9" + Identifier.getPath() + "§r";
            this.tooltip = Tooltip.create(Component.literal(this.name + "\n\n" + tag));
        }

        public void reset() {
            show = showByDefault;
            toggleVisible.selected = show;
        }

        private void toggleVisible(Button btn) {
            show = toggleVisible.selected;
        }

        public void setVisible(boolean show) {
            this.show = show;
        }

        @Override
        public Tooltip tooltip() {
            return tooltip;
        }

        @Override
        public @NotNull Component getNarration() {
            return Component.empty();
        }

        @Override
        public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean isHovering, float partialTick) {
            final int top = getY();
            final int left = getX();
            final int width = getWidth();
            final int height = getHeight();
            final int xMin = left + 2;
            final int yMin = top + 2;
            final int xMax = xMin + iconWidth;
            final int yMax = yMin + iconHeight;

            if (isHovering) {
                guiGraphics.fill(left, top, left + width, top + height, 0x80FFFFFF);
            }

            if (item != null) {
                guiGraphics.renderItem(itemStack, xMin, yMin);
            } else {
                WorldPreviewClient.renderTexture(guiGraphics, iconTextureId, xMin, yMin, xMax, yMax);
            }

            String formatName = isPrimaryNamespace ? name : "§o" + name;
            guiGraphics.drawString(minecraft.font, formatName, left + 16 + 4, top + 6, 0xFFFFFFFF);
            toggleVisible.setPosition(getRowRight() - 22, top);
            toggleVisible.render(guiGraphics, mouseX, mouseY, partialTick);
        }


        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            if (toggleVisible.isMouseOver(event.x(), event.y())) {
                toggleVisible.onClick(event, isDoubleClick);
            }
            return true;
        }

        public String name() {
            return name;
        }

        public boolean showByDefault() {
            return showByDefault;
        }

        public boolean show() {
            return show;
        }

        public short id() {
            return id;
        }

        public Item item() {
            return item;
        }

        public ItemStack itemStack() {
            return itemStack;
        }
    }

}
