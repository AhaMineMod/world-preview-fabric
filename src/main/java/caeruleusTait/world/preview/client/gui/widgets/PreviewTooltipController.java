package caeruleusTait.world.preview.client.gui.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;

public final class PreviewTooltipController {
    private PreviewTooltipController() {
    }

    public static String nameFormatter(String s) {
        int idx = s.indexOf(':');
        if (idx < 0) {
            return "§e" + s + "§r";
        }
        return String.format("§5§o%s§r§5:%s§r", s.substring(0, idx), s.substring(idx + 1));
    }

    public static void setTooltipNow(Minecraft minecraft, GuiGraphics guiGraphics, Tooltip tooltip, double mouseX, double mouseY) {
        guiGraphics.setTooltipForNextFrame(minecraft.font, tooltip.toCharSequence(minecraft), (int) mouseX, (int) mouseY);
    }
}
