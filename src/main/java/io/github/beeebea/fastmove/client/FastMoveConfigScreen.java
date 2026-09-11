package io.github.beeebea.fastmove.client;

import io.github.beeebea.fastmove.config.FMClientConfig;
import io.github.beeebea.fastmove.config.FMConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Forge 1.20.1 does not generate a config screen from ForgeConfigSpec, so FastMove supplies a small native screen. */
public final class FastMoveConfigScreen extends Screen {
    private final Screen parent;

    public FastMoveConfigScreen(Screen parent) {
        super(Component.translatable("text.config.fastmove.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = width / 2 - 100;
        int y = height / 2 - 34;

        addRenderableWidget(Button.builder(
                Component.translatable("text.config.fastmove.common"),
                button -> minecraft.setScreen(FastMoveConfigSectionScreen.gameplay(this, FMConfig.SPEC))
        ).bounds(x, y, 200, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("text.config.fastmove.client"),
                button -> minecraft.setScreen(FastMoveConfigSectionScreen.client(this, FMClientConfig.SPEC))
        ).bounds(x, y + 26, 200, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.done"),
                button -> onClose()
        ).bounds(x, y + 68, 200, 20).build());
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 24, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
