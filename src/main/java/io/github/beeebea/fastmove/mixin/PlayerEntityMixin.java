package io.github.beeebea.fastmove.mixin;

import io.github.beeebea.fastmove.FastMove;
import io.github.beeebea.fastmove.IFastPlayer;
import io.github.beeebea.fastmove.MoveState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DeathMessageType;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerEntityMixin extends LivingEntity implements IFastPlayer {
    protected PlayerEntityMixin(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Shadow public abstract boolean isLocalPlayer();
    @Shadow protected abstract void updatePlayerPose();
    @Shadow public abstract Abilities getAbilities();
    @Shadow public abstract FoodData getFoodData();

    @Unique private MoveState moveState = MoveState.NONE;
    @Unique private MoveState lastMoveState = MoveState.NONE;
    @Unique private Vec3 bonusVelocity = Vec3.ZERO;
    @Unique private int rollTickCounter = 0;
    @Unique private int wallRunCounter = 0;
    @Unique private Vec3 lastWallDir = Vec3.ZERO;
    @Unique private boolean isWallLeft = false;
    @Unique private int slideCooldown = 0;
    @Unique private int diveCooldown = 0;
    @Unique private BlockPos lastBlockPos = null;
    @Unique private boolean fastmove_lastSprintingState = false;

    @Override
    public MoveState fastmove_getMoveState() {
        return moveState;
    }

    @Override
    public void fastmove_setMoveState(MoveState moveState) {
        this.moveState = moveState;
    }

    @Override
    public void fastmove_setJumpInput(boolean input) {
    }

    @Unique
    private void fastmove_updateCurrentMoveState() {
        if (lastMoveState != moveState) {
            lastMoveState = moveState;
            if (moveState == MoveState.ROLLING || moveState == MoveState.PRONE) {
                rollTickCounter = 0;
                setPose(Pose.SWIMMING);
            }
            if (this.isLocalPlayer()) {
                FastMove.moveStateUpdater.setMoveState((Player) (Object) this, moveState);
            }
            FastMove.moveStateUpdater.setAnimationState((Player) (Object) this, moveState);
            updatePlayerPose();
            refreshDimensions();
        }
    }

    @Unique
    private static Vec3 fastmove_movementInputToVelocity(Vec3 movementInput, double speed, float yaw) {
        double d = movementInput.lengthSqr();
        if (d < 1.0E-7) {
            return Vec3.ZERO;
        } else {
            Vec3 vec3 = (d > 1.0 ? movementInput.normalize() : movementInput).scale(speed);
            double f = Mth.sin(yaw * 0.017453292F);
            double g = Mth.cos(yaw * 0.017453292F);
            return new Vec3(vec3.x * g - vec3.z * f, vec3.y, vec3.z * g + vec3.x * f);
        }
    }

    @Unique
    private static Vec3 fastmove_velocityToMovementInput(Vec3 velocity, float yaw) {
        double d = velocity.lengthSqr();
        if (d < 1.0E-7) {
            return Vec3.ZERO;
        }
        float f = Mth.sin(yaw * 0.017453292F);
        float g = Mth.cos(yaw * 0.017453292F);
        Vec3 unrotatedVec = new Vec3(
                velocity.x * g + velocity.z * f,
                velocity.y,
                -velocity.x * f + velocity.z * g
        );
        return (unrotatedVec.lengthSqr() > 1.0 ? unrotatedVec.normalize() : unrotatedVec);
    }

    @Unique
    private void fastmove_WallRun() {
        Vec3 vel = getDeltaMovement();
        boolean hasWall = fastmove_getWallDirection();

        if (moveState == MoveState.WALLRUNNING_LEFT || moveState == MoveState.WALLRUNNING_RIGHT) {
            if (!hasWall || onGround()) {
                wallRunCounter = 0;
                moveState = MoveState.NONE;
            } else {
                wallRunCounter++;
                setSprinting(true);

                BlockPos wallBlockPos = blockPosition().subtract(BlockPos.containing(lastWallDir));
                if (lastBlockPos == null || !lastBlockPos.equals(wallBlockPos)) {
                    lastBlockPos = wallBlockPos;
                    playStepSound(wallBlockPos, level().getBlockState(wallBlockPos));
                }

                Vec3 flatVel = vel.multiply(1, 0, 1);
                if (flatVel.lengthSqr() < 1.0E-7) return;
                Vec3 wallVel = isWallLeft ? flatVel.normalize().yRot(90.0F) : flatVel.normalize().yRot(-90.0F);
                moveState = !isWallLeft ? MoveState.WALLRUNNING_LEFT : MoveState.WALLRUNNING_RIGHT;
                if (fastmove_velocityToMovementInput(flatVel, getYRot()).dot(lastWallDir) < 0) {
                    addDeltaMovement(wallVel.multiply(-0.1, 0, -0.1));
                }
                addDeltaMovement(new Vec3(0, -vel.y * (1 - ((double) wallRunCounter / FastMove.getConfig().wallRunDurationTicks())), 0));
                bonusVelocity = Vec3.ZERO;
                if (!FastMove.INPUT.ismoveUpKeyPressed()) {
                    double velocityMult = FastMove.getConfig().wallRunSpeedBoostMultiplier();
                    addDeltaMovement(wallVel.multiply(0.3 * velocityMult, 0, 0.3 * velocityMult).add(new Vec3(0, 0.4 * velocityMult, 0)));
                    moveState = MoveState.NONE;
                }
            }
        } else {
            wallRunCounter = 0;
            if (!onGround() && FastMove.INPUT.ismoveUpKeyPressed() && hasWall && vel.y <= 0) {
                moveState = MoveState.WALLRUNNING_LEFT;
                getFoodData().addExhaustion(FastMove.getConfig().wallRunStaminaCost());
            }
        }
    }

    @Unique
    private boolean fastmove_getWallDirection() {
        Vec3 flat = getDeltaMovement().multiply(1, 0, 1);
        if (flat.lengthSqr() < 0.01) return false;
        flat = flat.normalize();
        Level world = level();
        Vec3 left = flat.yRot(-90.0F).multiply(0.5, 0, 0.5);
        Vec3 right = flat.yRot(90.0F).multiply(0.5, 0, 0.5);

        HitResult lowerLeftHit = world.clip(new ClipContext(position().add(0, 0.2, 0), position().add(left), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (lowerLeftHit.getType() == HitResult.Type.BLOCK) {
            HitResult upperLeftHit = world.clip(new ClipContext(position().add(0, 1.5, 0), position().add(left), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (upperLeftHit.getType() == HitResult.Type.BLOCK) {
                lastWallDir = Vec3.atCenterOf(blockPosition()).subtract(Vec3.atCenterOf(((BlockHitResult) lowerLeftHit).getBlockPos()));
                isWallLeft = true;
                return true;
            }
        }

        HitResult lowerRightHit = world.clip(new ClipContext(position().add(0, 0.2, 0), position().add(right), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (lowerRightHit.getType() == HitResult.Type.BLOCK) {
            HitResult upperRightHit = world.clip(new ClipContext(position().add(0, 1.5, 0), position().add(right), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (upperRightHit.getType() == HitResult.Type.BLOCK) {
                lastWallDir = Vec3.atCenterOf(blockPosition()).subtract(Vec3.atCenterOf(((BlockHitResult) lowerRightHit).getBlockPos()));
                isWallLeft = false;
                return true;
            }
        }
        lastWallDir = Vec3.ZERO;
        return false;
    }

    @Unique
    private boolean fastmove_isValidForMovement(boolean canSwim, boolean canElytra) {
        return !isSpectator() && (canElytra || !isFallFlying()) && (canSwim || !isInWater()) && !onClimbable() && !getAbilities().flying;
    }

    @Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
    public void fastmove_getDefaultDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        MoveState state = fastmove_getMoveState();
        if (state != null && state != MoveState.NONE) cir.setReturnValue(state.dimensions);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void fastmove_tick(CallbackInfo info) {
        if (!FastMove.getConfig().enableFastMove()) return;

        if (this.isLocalPlayer()) {
            if (getAbilities().flying || getControlledVehicle() != null) {
                moveState = MoveState.NONE;
                fastmove_updateCurrentMoveState();
                return;
            }
            double bonusDecay = 0.9;
            if (moveState == MoveState.ROLLING) {
                rollTickCounter++;
                if (rollTickCounter >= 10) {
                    rollTickCounter = 0;
                    moveState = FastMove.INPUT.ismoveDownKeyPressed() ? MoveState.PRONE : MoveState.NONE;
                }
                bonusDecay = 0.98;
            }
            if (moveState == MoveState.SLIDING) {
                if (!FastMove.INPUT.ismoveDownKeyPressed()) {
                    moveState = MoveState.NONE;
                }
            }

            if (FastMove.getConfig().wallRunEnabled()) fastmove_WallRun();

            addDeltaMovement(bonusVelocity);
            bonusVelocity = bonusVelocity.multiply(bonusDecay, 0, bonusDecay);
        }

        fastmove_updateCurrentMoveState();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void fastmove_tick_tail(CallbackInfo info) {
        if (!FastMove.getConfig().enableFastMove()) return;
        if (moveState == MoveState.PRONE || moveState == MoveState.ROLLING) setPose(Pose.SWIMMING);
        if (diveCooldown > 0) diveCooldown--;
        if (slideCooldown > 0) slideCooldown--;
    }

    @Inject(method = "travel", at = @At("HEAD"))
    private void fastmove_travel(Vec3 movementInput, CallbackInfo info) {
        if (!isLocalPlayer() || !FastMove.getConfig().enableFastMove() || getAbilities().flying || getControlledVehicle() != null) return;
        fastmove_lastSprintingState = isSprinting();
        if (FastMove.INPUT.ismoveDownKeyPressed()) {
            if (!FastMove.INPUT.ismoveDownKeyPressedLastTick()) {
                var conf = FastMove.getConfig();
                if (diveCooldown == 0 && fastmove_hasStamina(conf.diveRollStaminaCost(), true) && conf.diveRollEnabled() && !onGround()
                        && getDeltaMovement().multiply(1, 0, 1).lengthSqr() > 0.05
                        && fastmove_isValidForMovement(conf.diveRollWhenSwimming(), conf.diveRollWhenFlying())) {
                    diveCooldown = conf.diveRollCoolDown();
                    fastmove_useStamina(conf.diveRollStaminaCost(), true);
                    moveState = MoveState.ROLLING;
                    bonusVelocity = fastmove_movementInputToVelocity(new Vec3(0, 0, 1), 0.1f * conf.diveRollSpeedBoostMultiplier(), getYRot());
                    setSprinting(true);

                } else if (slideCooldown == 0 && fastmove_hasStamina(conf.slideStaminaCost(), false) && conf.slideEnabled() && fastmove_lastSprintingState
                        && fastmove_isValidForMovement(false, false)) {
                    // Intentional old FastMove 1.0.7 behavior: do NOT require onGround() here.
                    // This keeps jump-slide-jump air boosting intact for this NeoForge port.
                    slideCooldown = conf.slideCoolDown();
                    fastmove_useStamina(conf.slideStaminaCost(), false);
                    moveState = MoveState.SLIDING;
                    bonusVelocity = fastmove_movementInputToVelocity(new Vec3(0, 0, 1), 0.2f * conf.slideSpeedBoostMultiplier(), getYRot());
                    setSprinting(true);
                }
            }
        } else {
            if (moveState == MoveState.PRONE) {
                moveState = MoveState.NONE;
            }
        }
    }

    @Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
    private void fastmove_adjustMovementForSneaking(Vec3 movement, MoverType moverType, CallbackInfoReturnable<Vec3> cir) {
        if (this.isLocalPlayer()) {
            if (moveState == MoveState.ROLLING || moveState == MoveState.SLIDING) {
                cir.setReturnValue(movement);
            }
        }
    }

    @Inject(method = "jumpFromGround", at = @At("HEAD"))
    private void fastmove_jump(CallbackInfo info) {
        if (this.isLocalPlayer()) {
            setSprinting(fastmove_lastSprintingState);
            if (moveState == MoveState.SLIDING || moveState == MoveState.PRONE) {
                moveState = MoveState.NONE;
            }
            if (moveState == MoveState.ROLLING) {
                moveState = MoveState.PRONE;
            }
        }
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void fastmove_damage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (source.type().deathMessageType() == DeathMessageType.FALL_VARIANTS && moveState == MoveState.ROLLING) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    @Unique
    private void fastmove_useStamina(int amount, boolean isRoll) {
        getFoodData().addExhaustion(amount);
    }

    @Unique
    private boolean fastmove_hasStamina(int amount, boolean isRoll) {
        return getFoodData().getFoodLevel() > 0;
    }
}
