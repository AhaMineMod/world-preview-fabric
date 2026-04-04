package caeruleusTait.world.preview.client.gui.widgets.lists;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;

import java.util.Collection;

public abstract class BaseObjectSelectionList<E extends BaseObjectSelectionList.Entry<E>> extends ObjectSelectionList<E> {
    protected BaseObjectSelectionList(Minecraft minecraft, int width, int height, int x, int y, int itemHeight) {
        super(minecraft, width, height, y, itemHeight);
    }

    @Override
    public void setPosition(int x, int y) {
        setX(x);
        setY(y);
        // Re-apply scroll to force internal row layout refresh after moving.
        setScrollAmount(scrollAmount());
    }

    @Override
    public void setSize(int width, int height) {
        super.setSize(width, height);
        // Re-apply scroll to force internal row layout refresh after resizing.
        setScrollAmount(scrollAmount());
    }

    @Override
    public int getRowLeft() {
        return getX();
    }

    @Override
    public int getRowRight() {
        return getX() + width - 6;
    }

    @Override
    public int getRowWidth() {
        return this.width - 6;
    }

    @Override
    protected int scrollBarX() {
        return getRowRight();
    }

//    @Override
//    protected void renderSelection(GuiGraphics guiGraphics, int rowTop, int rowWidth, int innerHeight, int boxBorderColor, int boxInnerColor) {
//        int left = this.getRowLeft();
//        int right = this.getRowRight();
//        guiGraphics.fill(left, rowTop - 2, right, rowTop + innerHeight + 2, boxBorderColor);
//        guiGraphics.fill(left + 1, rowTop - 1, right - 1, rowTop + innerHeight + 1, boxInnerColor);
//    }

    @Override
    protected void renderSelection(GuiGraphics guiGraphics, E entry, int backgroundColor) {
        super.renderSelection(guiGraphics, entry, backgroundColor);
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);

        E hovered = getHovered();
        if (hovered != null && hovered.tooltip() != null) {
            guiGraphics.setTooltipForNextFrame(minecraft.font, hovered.tooltip().toCharSequence(minecraft), mouseX, mouseY);
        }
    }

    /**
     * Make public
     */
    @Override
    public void replaceEntries(Collection<E> entryList) {
        super.replaceEntries(entryList);
        // Keep viewport state coherent after entry replacement.
        setScrollAmount(scrollAmount());
    }

    public abstract static class Entry<E extends Entry<E>> extends ObjectSelectionList.Entry<E> {
        public Tooltip tooltip() {
            return null;
        }
    }
}
