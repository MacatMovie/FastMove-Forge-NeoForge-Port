package io.github.beeebea.fastmove;

public interface IFastPlayer {
    MoveState fastmove_getMoveState();
    void fastmove_setMoveState(MoveState moveState);
    void fastmove_setJumpInput(boolean input);

    /**
     * Client-side query used by the input hook to decide whether the FastMove down action
     * should consume vanilla sneak for this tick.
     */
    default boolean fastmove_shouldConsumeDownInput() {
        return false;
    }
}
