package caeruleusTait.world.preview.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.stream.Collectors;

public class WorldPreviewClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Nothing to do
    }

    public static void renderTexture(GuiGraphics guiGraphics, Identifier texture, int xMin, int yMin, int xMax, int yMax) {
        guiGraphics.blit(texture, xMin, yMin, xMax, yMax, 0.0F, 1.0F, 0.0F, 1.0F);
    }

    public static String toTitleCase(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        return Arrays
                .stream(input.split(" "))
                .map(StringUtils::capitalize)
                .collect(Collectors.joining(" "));
    }
}
