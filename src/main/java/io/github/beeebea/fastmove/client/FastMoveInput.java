package io.github.beeebea.fastmove.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.beeebea.fastmove.IFastMoveInput;
import io.github.beeebea.fastmove.IFastPlayer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public class FastMoveInput implements IFastMoveInput {
    private static final KeyMapping MOVE_UP_KEY = new KeyMapping(
            "key.fastmove.up",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.fastmove"
    );

    private static final KeyMapping MOVE_DOWN_KEY = new KeyMapping(
            "key.fastmove.down",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.fastmove"
    );

    private static final KeyMapping WALL_JUMP_KEY = new KeyMapping(
            "key.fastmove.wallJump",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.fastmove"
    );

    private boolean moveUpKeyPressed;
    private boolean moveDownKeyPressed;
    private boolean wallJumpKeyPressed;
    private boolean moveUpKeyPressedLastTick;
    private boolean moveDownKeyPressedLastTick;
    private boolean wallJumpKeyPressedLastTick;

    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(MOVE_UP_KEY);
        event.register(MOVE_DOWN_KEY);
        event.register(WALL_JUMP_KEY);
    }

    /**
     * Runs immediately after vanilla has sampled movement input. Sampling FastMove here keeps
     * the fallback Jump/Sneak controls and remapped FastMove controls on the exact same tick.
     *
     * When FastMove consumes the down action, prevent vanilla Sneak from affecting the same
     * movement tick. KeyboardInput has already applied vanilla's sneak-speed multiplier by the
     * time this event fires, so any already-scaled directional input is restored first.
     */
    public void onMovementInputUpdate(MovementInputUpdateEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || event.getEntity() != client.player) return;

        moveUpKeyPressedLastTick = moveUpKeyPressed;
        moveDownKeyPressedLastTick = moveDownKeyPressed;
        wallJumpKeyPressedLastTick = wallJumpKeyPressed;

        moveUpKeyPressed = MOVE_UP_KEY.isUnbound()
                ? client.options.keyJump.isDown()
                : MOVE_UP_KEY.isDown();
        moveDownKeyPressed = MOVE_DOWN_KEY.isUnbound()
                ? client.options.keyShift.isDown()
                : MOVE_DOWN_KEY.isDown();
        wallJumpKeyPressed = !WALL_JUMP_KEY.isUnbound() && WALL_JUMP_KEY.isDown();

        // Preserve very short presses that may begin and end between movement samples.
        while (!MOVE_UP_KEY.isUnbound() && MOVE_UP_KEY.consumeClick()) moveUpKeyPressed = true;
        while (!MOVE_DOWN_KEY.isUnbound() && MOVE_DOWN_KEY.consumeClick()) moveDownKeyPressed = true;
        while (!WALL_JUMP_KEY.isUnbound() && WALL_JUMP_KEY.consumeClick()) wallJumpKeyPressed = true;

        if (!moveDownKeyPressed || !((IFastPlayer) client.player).fastmove_shouldConsumeDownInput()) return;

        Input input = event.getInput();
        if (input.shiftKeyDown) {
            // Once FastMove consumes the down action, vanilla sneak must not also influence the
            // same movement tick. This is important both for the default Shift fallback and for
            // users who hold Shift while using a separately bound FastMove key.
            input.shiftKeyDown = false;

            // KeyboardInput has already multiplied directional input by vanilla's 0.3 sneak
            // factor. Undo that exact scaling instead of rebuilding input from WASD, so remapped
            // controls and other input providers keep their original direction/magnitude.
            input.forwardImpulse /= 0.3F;
            input.leftImpulse /= 0.3F;
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

    @Override
    public boolean isWallJumpKeyBound() {
        return !WALL_JUMP_KEY.isUnbound();
    }

    @Override
    public boolean isWallJumpKeyPressed() {
        return wallJumpKeyPressed;
    }

    @Override
    public boolean isWallJumpKeyPressedLastTick() {
        return wallJumpKeyPressedLastTick;
    }
}
