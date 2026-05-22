package caeruleusTait.world.preview.client;

import caeruleusTait.world.preview.client.gui.screens.MultiplayerPreviewScreen;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.stream.Collectors;

public class WorldPreviewClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientSendMessageEvents.COMMAND.register(MultiplayerPreviewScreen::markSentCommand);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> forwardSeedResponse(message));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> forwardSeedResponse(message));
    }

    private static void forwardSeedResponse(Component message) {
        MultiplayerPreviewScreen.handleSeedResponse(message);
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
