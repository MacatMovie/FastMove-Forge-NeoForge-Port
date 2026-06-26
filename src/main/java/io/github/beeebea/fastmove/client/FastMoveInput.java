package io.github.beeebea.fastmove.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.beeebea.fastmove.IFastMoveInput;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public class FastMoveInput implements IFastMoveInput {
    private static final KeyMapping MOVE_UP_KEY = new KeyMapping(
            "key.fastmove.up",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.movement"
    );

    private static final KeyMapping MOVE_DOWN_KEY = new KeyMapping(
            "key.fastmove.down",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.movement"
    );

    private boolean moveUpKeyPressed = false;
    private boolean moveDownKeyPressed = false;
    private boolean moveUpKeyPressedLastTick = false;
    private boolean moveDownKeyPressedLastTick = false;

    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(MOVE_UP_KEY);
        event.register(MOVE_DOWN_KEY);
    }

    public void onEndTick() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        moveUpKeyPressedLastTick = moveUpKeyPressed;
        moveDownKeyPressedLastTick = moveDownKeyPressed;

        if (MOVE_UP_KEY.isUnbound()) {
            moveUpKeyPressed = client.player.input.jumping;
        } else {
            moveUpKeyPressed = MOVE_UP_KEY.isDown();
            while (MOVE_UP_KEY.consumeClick()) {
                moveUpKeyPressed = true;
            }
        }

        if (MOVE_DOWN_KEY.isUnbound()) {
            moveDownKeyPressed = client.player.input.shiftKeyDown;
        } else {
            moveDownKeyPressed = MOVE_DOWN_KEY.isDown();
            while (MOVE_DOWN_KEY.consumeClick()) {
                moveDownKeyPressed = true;
            }
        }
    }

    @Override
    public boolean ismoveUpKeyPressed() {
        return moveUpKeyPressed;
    }

    @Override
    public boolean ismoveDownKeyPressed() {
        return moveDownKeyPressed;
    }

    @Override
    public boolean ismoveUpKeyPressedLastTick() {
        return moveUpKeyPressedLastTick;
    }

    @Override
    public boolean ismoveDownKeyPressedLastTick() {
        return moveDownKeyPressedLastTick;
    }
}
