package io.github.beeebea.fastmove.mixin;

import io.github.beeebea.fastmove.FastMove;
import io.github.beeebea.fastmove.IFastPlayer;
import io.github.beeebea.fastmove.MoveState;
import io.github.beeebea.fastmove.config.FMClientConfig;
import io.github.beeebea.fastmove.config.FMConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DeathMessageType;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
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
    @Unique private int rollTickCounter;
    @Unique private int wallRunCounter;
    @Unique private int slideCooldown;
    @Unique private int diveCooldown;
    @Unique private int bufferedSlideTicks;
    @Unique private boolean bufferedSlideWasSprinting;
    @Unique private static final double FASTMOVE_CRAWL_TRANSITION_SPEED = 0.075D;
    @Unique private double slideSpeed;
    @Unique private double slideStartSpeed;
    @Unique private int slideTickCounter;
    @Unique private int wallRunReentryCooldown;
    @Unique private boolean fastmove_lastSprintingState;

    @Unique private Vec3 movementStartDirection = Vec3.ZERO;
    @Unique private double movementStartHorizontalSpeed;

    @Unique private Vec3 wallNormal = Vec3.ZERO;
    @Unique private Vec3 wallTangent = Vec3.ZERO;
    @Unique private double wallRunSpeed;
    @Unique private boolean isWallLeft;
    @Unique private BlockPos wallBlockPos;
    @Unique private BlockPos lastStepSoundBlockPos;
    @Unique private Vec3 wallRunApproachVelocity = Vec3.ZERO;

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

    @Override
    public boolean fastmove_shouldConsumeDownInput() {
        if (!this.isLocalPlayer()) return false;

        FMConfig config = FastMove.getConfig();
        if (!config.enableFastMove() || getAbilities().flying || getControlledVehicle() != null) return false;

        if (moveState == MoveState.SLIDING || moveState == MoveState.ROLLING || moveState == MoveState.PRONE) {
            return true;
        }
        if (bufferedSlideTicks > 0) return true;

        Vec3 flatVelocity = getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
        boolean diveCandidate = diveCooldown == 0
                && fastmove_hasHungerForMovement()
                && config.diveRollEnabled()
                && !onGround()
                && flatVelocity.lengthSqr() > 0.05D
                && fastmove_isValidForMovement(config.diveRollWhenSwimming(), config.diveRollWhenFlying());
        if (diveCandidate) return true;

        return fastmove_canStartSlide(config, isSprinting());
    }

    @Unique
    private void fastmove_updateCurrentMoveState() {
        if (lastMoveState == moveState) return;

        lastMoveState = moveState;
        rollTickCounter = 0;
        if (moveState == MoveState.ROLLING || moveState == MoveState.PRONE) {
            setPose(Pose.SWIMMING);
        }
        if (this.isLocalPlayer()) {
            FastMove.moveStateUpdater.setMoveState((Player) (Object) this, moveState);
        }
        FastMove.moveStateUpdater.setAnimationState((Player) (Object) this, moveState);
        updatePlayerPose();
        // In open two-block-high space vanilla immediately prefers standing again. Re-assert the
        // crawl pose so holding FastMove crawl behaves like intentional crawling rather than only
        // working when a low ceiling happens to force it.
        if (moveState == MoveState.ROLLING || moveState == MoveState.PRONE) {
            setPose(Pose.SWIMMING);
        }
        refreshDimensions();
    }

    @Unique
    private void fastmove_cancelMovementBoost() {
        moveState = MoveState.NONE;
        bonusVelocity = Vec3.ZERO;
        rollTickCounter = 0;
        wallRunCounter = 0;
        bufferedSlideTicks = 0;
        slideSpeed = 0.0D;
        slideStartSpeed = 0.0D;
        slideTickCounter = 0;
        wallRunReentryCooldown = 0;
        wallNormal = Vec3.ZERO;
        wallTangent = Vec3.ZERO;
        wallRunApproachVelocity = Vec3.ZERO;
        fastmove_updateCurrentMoveState();
    }

    @Unique
    private static Vec3 fastmove_movementInputToVelocity(Vec3 movementInput, double speed, float yaw) {
        double length = movementInput.lengthSqr();
        if (length < 1.0E-7) return Vec3.ZERO;

        Vec3 scaled = (length > 1.0D ? movementInput.normalize() : movementInput).scale(speed);
        double sin = Mth.sin(yaw * ((float) Math.PI / 180F));
        double cos = Mth.cos(yaw * ((float) Math.PI / 180F));
        return new Vec3(scaled.x * cos - scaled.z * sin, scaled.y, scaled.z * cos + scaled.x * sin);
    }

    @Unique
    private void fastmove_captureMovementStart() {
        Vec3 flat = getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
        movementStartHorizontalSpeed = flat.length();
        movementStartDirection = movementStartHorizontalSpeed > 1.0E-7
                ? flat.scale(1.0D / movementStartHorizontalSpeed)
                : fastmove_movementInputToVelocity(new Vec3(0, 0, 1), 1.0D, getYRot());
    }

    @Unique
    private void fastmove_applyBalancedJumpMomentum(double originalBoost, double multiplier, double boostCap) {
        Vec3 current = getDeltaMovement();
        Vec3 flat = current.multiply(1.0D, 0.0D, 1.0D);
        Vec3 direction = flat.lengthSqr() > 1.0E-7 ? flat.normalize() : movementStartDirection;
        if (direction.lengthSqr() < 1.0E-7) return;

        // Use whichever is faster: the speed the player entered the move with or the speed they
        // still have at the moment of jumping. This lets a jump out of a fresh slide feel like a
        // small forward launch instead of accidentally reducing the slide's current momentum.
        double baseSpeed = Math.max(Math.max(0.0D, movementStartHorizontalSpeed), flat.length());
        double targetSpeed;
        if (boostCap > 0.0D && baseSpeed < boostCap) {
            targetSpeed = Math.min(boostCap, baseSpeed + originalBoost * multiplier);
        } else {
            // Never slow players who entered the move with speed from another source.
            targetSpeed = baseSpeed + (boostCap > 0.0D ? 0.0D : originalBoost * multiplier);
        }
        setDeltaMovement(direction.x * targetSpeed, current.y, direction.z * targetSpeed);
        bonusVelocity = Vec3.ZERO;
    }

    @Unique
    private void fastmove_scaleLegacyJumpMomentum(double multiplier) {
        double strength = Mth.clamp(multiplier, 0.0D, 2.0D);
        if (Math.abs(strength - 1.0D) < 1.0E-7D) return;

        Vec3 current = getDeltaMovement();
        Vec3 flat = current.multiply(1.0D, 0.0D, 1.0D);
        double currentSpeed = flat.length();
        double entrySpeed = Math.max(0.0D, movementStartHorizontalSpeed);

        // Only scale the speed gained after entering the slide/roll. That keeps the move itself at
        // its original feel while directly controlling how much of the chainable legacy boost is
        // carried into the next jump. A strength of 1.0 is byte-for-byte equivalent in behaviour
        // to the previous legacy mode; 0.0 keeps at most the speed the player entered with.
        if (currentSpeed > entrySpeed + 1.0E-7D) {
            Vec3 direction = flat.scale(1.0D / currentSpeed);
            double targetSpeed = entrySpeed + (currentSpeed - entrySpeed) * strength;
            setDeltaMovement(direction.x * targetSpeed, current.y, direction.z * targetSpeed);
        }

        // Legacy bonus velocity normally continues decaying after the jump. Scale that carry-over
        // by the same amount so reduced strengths do not immediately rebuild the speed we removed.
        bonusVelocity = bonusVelocity.scale(strength);
    }

    @Unique
    private boolean fastmove_isWallRunning() {
        return moveState == MoveState.WALLRUNNING_LEFT || moveState == MoveState.WALLRUNNING_RIGHT;
    }

    @Unique
    private void fastmove_endWallRun() {
        wallRunCounter = 0;
        wallNormal = Vec3.ZERO;
        wallTangent = Vec3.ZERO;
        wallRunSpeed = 0.0D;
        wallBlockPos = null;
        moveState = MoveState.NONE;
    }

    @Unique
    private void fastmove_dropFromExhaustedWallRun(FMConfig config) {
        Vec3 velocity = getDeltaMovement();
        Vec3 tangent = wallTangent.lengthSqr() > 1.0E-7 ? wallTangent.normalize() : Vec3.ZERO;

        // Finish below the wall-run start threshold and remove any leftover upward lift. This makes
        // momentum exhaustion feel like actually dropping from the wall instead of hopping upward
        // and immediately re-latching one block higher while the player is still holding Jump.
        double exitSpeed = Math.max(0.0D, Math.min(wallRunSpeed, config.wallRunMinimumSpeed() * 0.75D));
        if (tangent.lengthSqr() > 1.0E-7) {
            setDeltaMovement(tangent.x * exitSpeed, Math.min(velocity.y, 0.0D), tangent.z * exitSpeed);
        } else {
            Vec3 flat = velocity.multiply(1.0D, 0.0D, 1.0D);
            Vec3 direction = flat.lengthSqr() > 1.0E-7 ? flat.normalize() : Vec3.ZERO;
            setDeltaMovement(direction.x * exitSpeed, Math.min(velocity.y, 0.0D), direction.z * exitSpeed);
        }

        wallRunApproachVelocity = Vec3.ZERO;
        wallRunReentryCooldown = 8;
        fastmove_endWallRun();
    }

    @Unique
    private void fastmove_wallJump() {
        FMConfig config = FastMove.getConfig();
        double multiplier = config.wallJumpBoostMultiplier();
        Vec3 tangent = wallTangent.lengthSqr() > 1.0E-7 ? wallTangent.normalize() : Vec3.ZERO;
        Vec3 outward = wallNormal.lengthSqr() > 1.0E-7 ? wallNormal.normalize() : Vec3.ZERO;

        double forwardSpeed = Math.max(
                wallRunSpeed,
                Math.abs(getDeltaMovement().multiply(1.0D, 0.0D, 1.0D).dot(tangent))
        );
        double boostCap = config.wallRunBoostSpeedCap();
        if (forwardSpeed < boostCap) {
            forwardSpeed = Math.min(
                    boostCap,
                    forwardSpeed + config.wallRunEntrySpeedBoost() * 0.75D * multiplier
            );
        }

        Vec3 launch = tangent.scale(forwardSpeed)
                .add(outward.scale(0.3D * multiplier))
                .add(0.0D, 0.4D * multiplier, 0.0D);
        setDeltaMovement(launch);
        bonusVelocity = Vec3.ZERO;
        fastmove_endWallRun();
    }

    @Unique
    private void fastmove_wallRun() {
        FMConfig config = FastMove.getConfig();
        Vec3 velocity = getDeltaMovement();

        if (fastmove_isWallRunning()) {
            boolean dedicatedJumpPressed = FastMove.INPUT.isWallJumpKeyBound()
                    && FastMove.INPUT.isWallJumpKeyPressed()
                    && !FastMove.INPUT.isWallJumpKeyPressedLastTick();
            boolean releasedSharedKey = !FastMove.INPUT.isWallJumpKeyBound()
                    && !FastMove.INPUT.ismoveUpKeyPressed();

            if (dedicatedJumpPressed || releasedSharedKey) {
                fastmove_wallJump();
                return;
            }

            if (!FastMove.INPUT.ismoveUpKeyPressed() || onGround() || !fastmove_refreshWallContact()) {
                fastmove_endWallRun();
                return;
            }
            if (wallRunCounter >= config.wallRunDurationTicks()) {
                fastmove_dropFromExhaustedWallRun(config);
                return;
            }

            wallRunCounter++;
            wallRunSpeed = Math.max(0.0D, wallRunSpeed - config.wallRunSpeedDecayPerTick());
            if (wallRunSpeed < config.wallRunMinimumSpeed()) {
                fastmove_dropFromExhaustedWallRun(config);
                return;
            }

            setSprinting(true);
            fastmove_playWallStepSound();

            double verticalSpeed = Math.max(velocity.y, -config.wallRunFallSpeed());
            Vec3 cling = wallNormal.scale(-0.02D);
            setDeltaMovement(
                    wallTangent.x * wallRunSpeed + cling.x,
                    verticalSpeed,
                    wallTangent.z * wallRunSpeed + cling.z
            );
            bonusVelocity = Vec3.ZERO;
            moveState = isWallLeft ? MoveState.WALLRUNNING_LEFT : MoveState.WALLRUNNING_RIGHT;
            return;
        }

        wallRunCounter = 0;
        if (wallRunReentryCooldown > 0 || onGround() || !FastMove.INPUT.ismoveUpKeyPressed()) return;
        if (!fastmove_hasHungerForMovement()) return;
        if (!fastmove_findInitialWallContact()) return;

        Vec3 flatVelocity = velocity.multiply(1.0D, 0.0D, 1.0D);
        // Wall contact is often detected one tick after collision has already removed the velocity
        // pointing into the wall. Keep the previous pre-collision horizontal velocity so entering a
        // wall run does not mysteriously throw away most of a sprint/jump's momentum.
        Vec3 entryReference = wallRunApproachVelocity.lengthSqr() > flatVelocity.lengthSqr()
                ? wallRunApproachVelocity
                : flatVelocity;
        double totalHorizontalSpeed = entryReference.length();
        if (totalHorizontalSpeed < config.wallRunMinimumSpeed()) {
            wallNormal = Vec3.ZERO;
            wallTangent = Vec3.ZERO;
            return;
        }
        double alongWallSpeed = Math.max(
                Math.abs(flatVelocity.dot(wallTangent)),
                Math.abs(entryReference.dot(wallTangent))
        );
        if (alongWallSpeed < config.wallRunMinimumSpeed()) {
            wallNormal = Vec3.ZERO;
            wallTangent = Vec3.ZERO;
            return;
        }

        double boostCap = config.wallRunBoostSpeedCap();
        // Below the eligibility cap, preserve the full approach speed by redirecting it along the
        // wall and add a small entry boost. Above the cap, preserve only genuine along-wall speed so
        // wall-to-wall jumps cannot turn their outward component into infinite forward acceleration.
        wallRunSpeed = totalHorizontalSpeed < boostCap ? totalHorizontalSpeed : alongWallSpeed;
        if (totalHorizontalSpeed < boostCap) {
            wallRunSpeed = Math.min(boostCap, wallRunSpeed + config.wallRunEntrySpeedBoost());
        }

        setSprinting(true);
        double verticalSpeed = Math.max(velocity.y, -config.wallRunFallSpeed());
        Vec3 cling = wallNormal.scale(-0.02D);
        setDeltaMovement(
                wallTangent.x * wallRunSpeed + cling.x,
                verticalSpeed,
                wallTangent.z * wallRunSpeed + cling.z
        );
        bonusVelocity = Vec3.ZERO;
        moveState = isWallLeft ? MoveState.WALLRUNNING_LEFT : MoveState.WALLRUNNING_RIGHT;
    }
    @Unique
    private boolean fastmove_findInitialWallContact() {
        Vec3 flatVelocity = getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
        Vec3 reference = wallRunApproachVelocity.lengthSqr() > flatVelocity.lengthSqr()
                ? wallRunApproachVelocity
                : flatVelocity;
        // Wall-running always requires real horizontal X/Z momentum. Vertical jump/fall speed
        // never contributes to this check.
        double minimumHorizontalSpeed = FastMove.getConfig().wallRunMinimumSpeed();
        if (reference.length() < minimumHorizontalSpeed) return false;

        // Probe from the player's current movement direction when possible because it best describes
        // which side the wall is physically on; use the pre-collision reference if collision already
        // removed almost all horizontal motion.
        Vec3 probeReference = flatVelocity.lengthSqr() > 1.0E-4 ? flatVelocity : reference;
        Vec3 direction = probeReference.normalize();
        Vec3 leftProbe = new Vec3(direction.z, 0.0D, -direction.x).scale(0.65D);
        Vec3 rightProbe = leftProbe.scale(-1.0D);
        return fastmove_tryWallProbe(leftProbe, reference, true)
                || fastmove_tryWallProbe(rightProbe, reference, true);
    }

    @Unique
    private boolean fastmove_refreshWallContact() {
        if (wallNormal.lengthSqr() < 1.0E-7) return false;
        Vec3 probe = wallNormal.scale(-0.7D);
        Vec3 reference = wallTangent.lengthSqr() > 1.0E-7 ? wallTangent : getDeltaMovement().multiply(1, 0, 1);
        return fastmove_tryWallProbe(probe, reference, false);
    }

    @Unique
    private boolean fastmove_tryWallProbe(Vec3 probe, Vec3 directionReference, boolean requireAlongSpeed) {
        Level world = level();
        Vec3 lowerStart = position().add(0.0D, 0.2D, 0.0D);
        Vec3 upperStart = position().add(0.0D, 1.5D, 0.0D);
        BlockHitResult lowerHit = world.clip(new ClipContext(
                lowerStart, lowerStart.add(probe), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this
        ));
        if (lowerHit.getType() != HitResult.Type.BLOCK) return false;

        BlockHitResult upperHit = world.clip(new ClipContext(
                upperStart, upperStart.add(probe), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this
        ));
        if (upperHit.getType() != HitResult.Type.BLOCK) return false;

        Vec3 normal = Vec3.atLowerCornerOf(lowerHit.getDirection().getNormal()).multiply(1.0D, 0.0D, 1.0D);
        if (normal.lengthSqr() < 0.5D) return false;
        normal = normal.normalize();

        Vec3 tangent = new Vec3(-normal.z, 0.0D, normal.x);
        if (tangent.dot(directionReference) < 0.0D) tangent = tangent.scale(-1.0D);
        Vec3 alongReference = directionReference.multiply(1.0D, 0.0D, 1.0D);
        double alongSpeed = Math.abs(alongReference.dot(tangent));
        if (requireAlongSpeed && alongSpeed < FastMove.getConfig().wallRunMinimumSpeed()) return false;

        wallNormal = normal;
        wallTangent = tangent;
        wallBlockPos = lowerHit.getBlockPos();
        Vec3 towardWall = normal.scale(-1.0D);
        double sideCross = wallTangent.z * towardWall.x - wallTangent.x * towardWall.z;
        isWallLeft = sideCross > 0.0D;
        return true;
    }

    @Unique
    private void fastmove_playWallStepSound() {
        if (wallBlockPos == null || wallBlockPos.equals(lastStepSoundBlockPos)) return;
        lastStepSoundBlockPos = wallBlockPos;
        playStepSound(wallBlockPos, level().getBlockState(wallBlockPos));
    }

    @Inject(method = "playStepSound", at = @At("HEAD"), cancellable = true)
    private void fastmove_replaceSlideFootsteps(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (moveState != MoveState.SLIDING) return;

        // Vanilla footsteps make a body slide sound like the player is still walking. Reuse the
        // Breeze's built-in slide sound as a short scrape/whoosh pulse instead. Player step cadence
        // already follows distance travelled, so the repeats naturally spread out as the slide slows.
        double horizontalSpeed = getDeltaMovement().multiply(1.0D, 0.0D, 1.0D).length();
        float speedRatio = (float) Mth.clamp(horizontalSpeed / 0.50D, 0.0D, 1.0D);
        float volumeMultiplier = FMClientConfig.INSTANCE.slideSoundVolumePercent() / 100.0F;
        float volume = (0.22F + 0.16F * speedRatio) * volumeMultiplier;
        float pitch = 0.72F + 0.40F * speedRatio;
        if (volume > 0.0F) playSound(SoundEvents.BREEZE_SLIDE, volume, pitch);
        ci.cancel();
    }

    @Unique
    private boolean fastmove_isGroundNear(double distance) {
        if (onGround()) return true;
        Vec3 start = position().add(0.0D, 0.05D, 0.0D);
        BlockHitResult hit = level().clip(new ClipContext(
                start,
                start.add(0.0D, -distance, 0.0D),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this
        ));
        return hit.getType() == HitResult.Type.BLOCK;
    }

    @Unique
    private boolean fastmove_isValidForMovement(boolean canSwim, boolean canElytra) {
        return !isSpectator()
                && (canElytra || !isFallFlying())
                && (canSwim || !isInWater())
                && !onClimbable()
                && !getAbilities().flying;
    }

    @Unique
    private boolean fastmove_canStartSlide(FMConfig config, boolean wasSprinting) {
        return slideCooldown == 0
                && fastmove_hasHungerForMovement()
                && config.slideEnabled()
                && wasSprinting
                && onGround()
                && fastmove_isValidForMovement(false, false);
    }

    @Unique
    private void fastmove_startSlide(FMConfig config) {
        slideCooldown = config.slideCoolDown();
        bufferedSlideTicks = 0;
        fastmove_captureMovementStart();
        moveState = MoveState.SLIDING;
        fastmove_updateCurrentMoveState();

        slideTickCounter = 0;
        slideStartSpeed = 0.0D;

        if (config.legacyMovementBoosts()) {
            slideSpeed = 0.0D;
            bonusVelocity = fastmove_movementInputToVelocity(
                    new Vec3(0, 0, 1),
                    0.2D * config.slideSpeedBoostMultiplier(),
                    getYRot()
            );
        } else {
            Vec3 current = getDeltaMovement();
            Vec3 direction = movementStartDirection.lengthSqr() > 1.0E-7
                    ? movementStartDirection
                    : fastmove_movementInputToVelocity(new Vec3(0, 0, 1), 1.0D, getYRot());
            double boostCap = config.slideBoostSpeedCap();
            // Leave a little headroom below the overall boost cap so jumping out of the slide can
            // still provide a distinct forward launch. The entry boost is applied immediately when
            // the slide starts; it does not ramp up over the first few ticks.
            double entryBoostCap = boostCap * 0.92D;
            if (movementStartHorizontalSpeed < boostCap) {
                double boostedSpeed = movementStartHorizontalSpeed + 0.24D * config.slideSpeedBoostMultiplier();
                slideSpeed = Math.max(movementStartHorizontalSpeed, Math.min(entryBoostCap, boostedSpeed));
            } else {
                // Never slow external/potion/modded momentum that already exceeds FastMove's cap.
                slideSpeed = movementStartHorizontalSpeed;
            }
            slideStartSpeed = slideSpeed;
            setDeltaMovement(direction.x * slideSpeed, current.y, direction.z * slideSpeed);
            bonusVelocity = Vec3.ZERO;
        }
        setSprinting(true);
    }

    @Unique
    private void fastmove_startDiveRoll(FMConfig config) {
        diveCooldown = config.diveRollCoolDown();
        slideSpeed = 0.0D;
        slideStartSpeed = 0.0D;
        slideTickCounter = 0;
        fastmove_captureMovementStart();
        moveState = MoveState.ROLLING;
        fastmove_updateCurrentMoveState();
        bonusVelocity = fastmove_movementInputToVelocity(
                new Vec3(0, 0, 1),
                0.1D * config.diveRollSpeedBoostMultiplier(),
                getYRot()
        );
        setSprinting(true);
    }

    @Inject(method = "updatePlayerPose", at = @At("TAIL"))
    private void fastmove_keepHeldCrawlPose(CallbackInfo info) {
        if (moveState == MoveState.ROLLING || moveState == MoveState.PRONE) {
            setPose(Pose.SWIMMING);
        }
    }

    @Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
    public void fastmove_getDefaultDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        MoveState state = fastmove_getMoveState();
        // PRONE is the held-crawl state: use Minecraft's native SWIMMING/crawling dimensions.
        // Slide and roll retain FastMove's original low profile.
        if (state != null && state != MoveState.NONE && state != MoveState.PRONE) {
            cir.setReturnValue(state.dimensions);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void fastmove_tick(CallbackInfo info) {
        FMConfig config = FastMove.getConfig();
        if (!config.enableFastMove()) {
            if (moveState != MoveState.NONE || bonusVelocity.lengthSqr() > 1.0E-7 || slideSpeed > 1.0E-7
                    || rollTickCounter != 0 || wallRunCounter != 0 || bufferedSlideTicks != 0) {
                fastmove_cancelMovementBoost();
            }
            return;
        }

        if (!this.isLocalPlayer() && moveState == MoveState.ROLLING) {
            rollTickCounter++;
        }

        if (this.isLocalPlayer()) {
            if (getAbilities().flying || getControlledVehicle() != null) {
                fastmove_cancelMovementBoost();
                return;
            }

            double bonusDecay = 0.9D;
            if (moveState == MoveState.ROLLING) {
                rollTickCounter++;
                if (rollTickCounter >= config.rollDurationTicks()) {
                    rollTickCounter = 0;
                    moveState = FastMove.INPUT.ismoveDownKeyPressed() ? MoveState.PRONE : MoveState.NONE;
                    if (moveState == MoveState.PRONE) setSprinting(false);
                    if (!config.legacyMovementBoosts()) bonusVelocity = Vec3.ZERO;
                }
                bonusDecay = config.legacyMovementBoosts() ? 0.98D : 0.90D;
            }

            if (moveState == MoveState.SLIDING) {
                if (!FastMove.INPUT.ismoveDownKeyPressed()) {
                    moveState = MoveState.NONE;
                    slideSpeed = 0.0D;
                    slideStartSpeed = 0.0D;
                    slideTickCounter = 0;
                    if (!config.legacyMovementBoosts()) bonusVelocity = Vec3.ZERO;
                } else if (config.legacyMovementBoosts()) {
                    // Legacy mode keeps the original steerable slide + decaying bonus momentum,
                    // but still settles into the held crawl once the old boost has effectively ended.
                    if (bonusVelocity.multiply(1.0D, 0.0D, 1.0D).lengthSqr() <= 1.0E-4D) {
                        moveState = MoveState.PRONE;
                        bonusVelocity = Vec3.ZERO;
                        setSprinting(false);
                    }
                } else {
                    // Balanced slides use a fixed-duration ease curve rather than exponential decay.
                    // Exponential decay loses the most speed immediately and made entering a slide
                    // feel like entering Sneak. Quadratic progress does the opposite: the glide stays
                    // lively at first, then sheds momentum more strongly near the end before handing
                    // control to the held vanilla-style crawl.
                    if (horizontalCollision) {
                        slideSpeed = 0.0D;
                    } else {
                        slideTickCounter++;
                        double duration = Math.max(1.0D, config.slideDurationTicks());
                        double progress = Mth.clamp(slideTickCounter / duration, 0.0D, 1.0D);
                        double easedLoss = progress * progress;
                        double startSpeed = Math.max(slideStartSpeed, FASTMOVE_CRAWL_TRANSITION_SPEED);
                        slideSpeed = FASTMOVE_CRAWL_TRANSITION_SPEED
                                + (startSpeed - FASTMOVE_CRAWL_TRANSITION_SPEED) * (1.0D - easedLoss);
                    }

                    if (slideSpeed <= FASTMOVE_CRAWL_TRANSITION_SPEED
                            || slideTickCounter >= config.slideDurationTicks()) {
                        moveState = MoveState.PRONE;
                        slideSpeed = 0.0D;
                        slideStartSpeed = 0.0D;
                        slideTickCounter = 0;
                        bonusVelocity = Vec3.ZERO;
                        setSprinting(false);
                    } else {
                        Vec3 velocity = getDeltaMovement();
                        Vec3 direction = movementStartDirection.lengthSqr() > 1.0E-7
                                ? movementStartDirection
                                : velocity.multiply(1.0D, 0.0D, 1.0D).normalize();
                        setDeltaMovement(direction.x * slideSpeed, velocity.y, direction.z * slideSpeed);
                    }
                }
            }

            if (moveState == MoveState.PRONE) {
                setSprinting(false);
                slideSpeed = 0.0D;
                slideStartSpeed = 0.0D;
                slideTickCounter = 0;
                bonusVelocity = Vec3.ZERO;
            }

            if (config.wallRunEnabled()) {
                fastmove_wallRun();
            } else if (fastmove_isWallRunning()) {
                fastmove_endWallRun();
            }

            addDeltaMovement(bonusVelocity);
            bonusVelocity = bonusVelocity.multiply(bonusDecay, 0.0D, bonusDecay);
        }

        fastmove_updateCurrentMoveState();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void fastmove_tickTail(CallbackInfo info) {
        if (!FastMove.getConfig().enableFastMove()) return;
        if (moveState == MoveState.PRONE || moveState == MoveState.ROLLING) setPose(Pose.SWIMMING);
        if (diveCooldown > 0) diveCooldown--;
        if (slideCooldown > 0) slideCooldown--;
        if (bufferedSlideTicks > 0) bufferedSlideTicks--;
        if (wallRunReentryCooldown > 0) wallRunReentryCooldown--;
    }

    @ModifyVariable(method = "travel", at = @At("HEAD"), argsOnly = true)
    private Vec3 fastmove_adjustTravelInput(Vec3 movementInput) {
        if (!isLocalPlayer() || !FastMove.getConfig().enableFastMove()) return movementInput;

        // Balanced sliding/rolling carry their own speed rather than letting vanilla movement
        // acceleration change it. While sliding, however, still use the player's movement input as
        // steering so the slide can curve naturally just like normal running. We consume the input
        // afterwards, which keeps Shift and remapped FastMove keys identical and avoids Sneak drag.
        if (moveState == MoveState.SLIDING && !FastMove.getConfig().legacyMovementBoosts()) {
            Vec3 steeringInput = new Vec3(movementInput.x, 0.0D, movementInput.z);
            if (steeringInput.lengthSqr() > 1.0E-7D) {
                Vec3 desiredDirection = fastmove_movementInputToVelocity(steeringInput, 1.0D, getYRot());
                if (desiredDirection.lengthSqr() > 1.0E-7D) {
                    desiredDirection = desiredDirection.normalize();
                    Vec3 currentDirection = movementStartDirection.lengthSqr() > 1.0E-7D
                            ? movementStartDirection.normalize()
                            : desiredDirection;
                    // Responsive enough to steer like running, but slightly smoothed so tapping a
                    // perpendicular key does not snap the slide direction by 90 degrees in one tick.
                    Vec3 steeredDirection = currentDirection.scale(0.65D)
                            .add(desiredDirection.scale(0.35D));
                    if (steeredDirection.lengthSqr() > 1.0E-7D) {
                        movementStartDirection = steeredDirection.normalize();
                        if (slideSpeed > 0.0D) {
                            Vec3 velocity = getDeltaMovement();
                            setDeltaMovement(
                                    movementStartDirection.x * slideSpeed,
                                    velocity.y,
                                    movementStartDirection.z * slideSpeed
                            );
                        }
                    }
                }
            }
            return new Vec3(0.0D, movementInput.y, 0.0D);
        }
        if (moveState == MoveState.ROLLING && !FastMove.getConfig().legacyMovementBoosts()) {
            return new Vec3(0.0D, movementInput.y, 0.0D);
        }

        // Held FastMove crawl uses vanilla-like slow movement regardless of whether the action
        // comes from Shift or a remapped FastMove key.
        if (moveState == MoveState.PRONE) {
            return new Vec3(movementInput.x * 0.3D, movementInput.y, movementInput.z * 0.3D);
        }

        return movementInput;
    }

    @Inject(method = "travel", at = @At("HEAD"))
    private void fastmove_travel(Vec3 movementInput, CallbackInfo info) {
        if (!isLocalPlayer()) return;

        // Save the horizontal velocity immediately before vanilla movement/collision handling. If a
        // wall run begins on the following tick this gives us the player's real approach momentum.
        if (!fastmove_isWallRunning()) {
            wallRunApproachVelocity = getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
        }

        FMConfig config = FastMove.getConfig();
        if (!config.enableFastMove() || getAbilities().flying || getControlledVehicle() != null) {
            if (moveState != MoveState.NONE || bonusVelocity.lengthSqr() > 1.0E-7 || slideSpeed > 1.0E-7
                    || rollTickCounter != 0 || wallRunCounter != 0 || bufferedSlideTicks != 0) {
                fastmove_cancelMovementBoost();
            }
            return;
        }

        fastmove_lastSprintingState = isSprinting();

        if (bufferedSlideTicks > 0) {
            if (!FastMove.INPUT.ismoveDownKeyPressed()) {
                bufferedSlideTicks = 0;
            } else if (fastmove_canStartSlide(config, bufferedSlideWasSprinting)) {
                fastmove_startSlide(config);
                return;
            }
        }

        if (FastMove.INPUT.ismoveDownKeyPressed()) {
            if (!FastMove.INPUT.ismoveDownKeyPressedLastTick()) {
                Vec3 flatVelocity = getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
                boolean diveCandidate = diveCooldown == 0
                        && fastmove_hasHungerForMovement()
                        && config.diveRollEnabled()
                        && !onGround()
                        && flatVelocity.lengthSqr() > 0.05D
                        && fastmove_isValidForMovement(config.diveRollWhenSwimming(), config.diveRollWhenFlying());

                if (diveCandidate && config.preferSlideNearGround() && getDeltaMovement().y <= 0.0D
                        && config.slideEnabled() && fastmove_isGroundNear(config.nearGroundSlideDistance())) {
                    bufferedSlideTicks = config.slideInputBufferTicks();
                    bufferedSlideWasSprinting = fastmove_lastSprintingState || flatVelocity.lengthSqr() > 0.05D;
                } else if (diveCandidate) {
                    fastmove_startDiveRoll(config);
                } else if (fastmove_canStartSlide(config, fastmove_lastSprintingState)) {
                    fastmove_startSlide(config);
                }
            }
        } else if (moveState == MoveState.PRONE) {
            moveState = MoveState.NONE;
        }
    }

    @Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
    private void fastmove_adjustMovementForSneaking(Vec3 movement, MoverType moverType, CallbackInfoReturnable<Vec3> cir) {
        if (this.isLocalPlayer() && (moveState == MoveState.ROLLING || moveState == MoveState.SLIDING)) {
            cir.setReturnValue(movement);
        }
    }

    @Inject(method = "jumpFromGround", at = @At("HEAD"))
    private void fastmove_jump(CallbackInfo info) {
        if (!this.isLocalPlayer()) return;

        FMConfig config = FastMove.getConfig();
        setSprinting(fastmove_lastSprintingState);
        if (config.legacyMovementBoosts()) {
            boolean hasLegacyCarry = moveState == MoveState.SLIDING
                    || moveState == MoveState.ROLLING
                    || bonusVelocity.multiply(1.0D, 0.0D, 1.0D).lengthSqr() > 1.0E-7D;
            if (hasLegacyCarry) {
                fastmove_scaleLegacyJumpMomentum(config.legacyMomentumMultiplier());
            }
        } else {
            if (moveState == MoveState.SLIDING) {
                fastmove_applyBalancedJumpMomentum(0.2D, config.slideJumpMomentumMultiplier(), config.slideBoostSpeedCap());
            } else if (moveState == MoveState.ROLLING) {
                fastmove_applyBalancedJumpMomentum(0.1D, config.diveRollJumpMomentumMultiplier(), 0.0D);
            }
        }

        if (moveState == MoveState.SLIDING || moveState == MoveState.PRONE || moveState == MoveState.ROLLING) {
            moveState = MoveState.NONE;
            slideSpeed = 0.0D;
            slideStartSpeed = 0.0D;
            slideTickCounter = 0;
        }
    }

    @Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
    private void fastmove_cancelFullyProtectedFallLanding(float fallDistance, float multiplier, DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        if (!fastmove_isProtectedRollFall(source)) return;

        int rawDamage = calculateFallDamage(fallDistance, multiplier);
        if (fastmove_adjustProtectedFallDamage(rawDamage) <= 0.0F) {
            // Cancel the landing before LivingEntity's fall handling can emit hurt/fall feedback.
            // A perfectly protected roll should feel successful rather than sounding like damage.
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void fastmove_cancelFullyProtectedFallDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!fastmove_isProtectedRollFall(source)) return;
        if (fastmove_adjustProtectedFallDamage(amount) <= 0.0F) {
            // Returning before vanilla damage handling also suppresses the hurt sound/flash for a perfect roll.
            cir.setReturnValue(false);
        }
    }

    @ModifyVariable(method = "hurt", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float fastmove_reduceFallDamage(float amount, DamageSource source, float originalAmount) {
        return fastmove_isProtectedRollFall(source) ? fastmove_adjustProtectedFallDamage(amount) : amount;
    }

    @Unique
    private boolean fastmove_isProtectedRollFall(DamageSource source) {
        FMConfig config = FastMove.getConfig();
        return source.type().deathMessageType() == DeathMessageType.FALL_VARIANTS
                && moveState == MoveState.ROLLING
                && rollTickCounter < config.rollFallProtectionWindowTicks();
    }

    @Unique
    private float fastmove_adjustProtectedFallDamage(float amount) {
        FMConfig config = FastMove.getConfig();
        if (config.rollFullFallDamageImmunity()) return 0.0F;

        double reduced = Math.max(0.0D, amount - config.rollAdditionalSafeFallDistance());
        float adjusted = (float) (reduced * config.rollFallDamageMultiplier());
        // Minecraft can still play the hurt/fall feedback for tiny fractional damage that is barely
        // or not visibly reflected by the heart HUD. Treat anything below half a heart as a clean,
        // successful roll so the existing causeFallDamage cancellation suppresses that feedback too.
        return adjusted < 1.0F ? 0.0F : adjusted;
    }

    @Unique
    private boolean fastmove_hasHungerForMovement() {
        // Creative players can sprint at zero hunger and FastMove should follow the same rule.
        // Hunger/exhaustion is a survival mechanic, not a restriction on creative movement.
        return getAbilities().instabuild || getFoodData().getFoodLevel() > 0;
    }
}
