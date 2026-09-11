package io.github.beeebea.fastmove;

public interface IFastMoveInput {
    boolean ismoveUpKeyPressed();
    boolean ismoveDownKeyPressed();
    boolean ismoveUpKeyPressedLastTick();
    boolean ismoveDownKeyPressedLastTick();

    default boolean isWallJumpKeyBound() {
        return false;
    }

    default boolean isWallJumpKeyPressed() {
        return false;
    }

    default boolean isWallJumpKeyPressedLastTick() {
        return false;
    }
}

