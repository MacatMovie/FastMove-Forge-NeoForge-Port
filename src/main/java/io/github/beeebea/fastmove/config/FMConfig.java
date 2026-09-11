package io.github.beeebea.fastmove.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.common.ForgeConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Gameplay and balance settings. Server-synced copies are immutable snapshots. */
public final class FMConfig {
    public static final String FILE_NAME = "fastmove-common.toml";
    private static final String LEGACY_FILE_NAME = "fastmove.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LoggerFactory.getLogger("fastmove/config");

    public static final FMConfig LOCAL;
    public static final ForgeConfigSpec SPEC;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        LOCAL = new FMConfig(builder);
        SPEC = builder.build();
    }

    private final ForgeConfigSpec.BooleanValue enableFastMoveValue;
    private final ForgeConfigSpec.BooleanValue legacyMovementBoostsValue;
    private final ForgeConfigSpec.IntValue legacyMomentumStrengthPercentValue;

    private final ForgeConfigSpec.BooleanValue diveRollEnabledValue;
    private final ForgeConfigSpec.IntValue diveRollHungerCostValue;
    private final ForgeConfigSpec.DoubleValue diveRollSpeedBoostMultiplierValue;
    private final ForgeConfigSpec.DoubleValue diveRollJumpMomentumMultiplierValue;
    private final ForgeConfigSpec.IntValue diveRollCoolDownValue;
    private final ForgeConfigSpec.BooleanValue diveRollWhenSwimmingValue;
    private final ForgeConfigSpec.BooleanValue diveRollWhenFlyingValue;
    private final ForgeConfigSpec.BooleanValue preferSlideNearGroundValue;
    private final ForgeConfigSpec.DoubleValue nearGroundSlideDistanceValue;
    private final ForgeConfigSpec.IntValue slideInputBufferTicksValue;
    private final ForgeConfigSpec.IntValue rollDurationTicksValue;
    private final ForgeConfigSpec.IntValue rollFallProtectionWindowTicksValue;
    private final ForgeConfigSpec.DoubleValue rollFallDamageMultiplierValue;
    private final ForgeConfigSpec.DoubleValue rollAdditionalSafeFallDistanceValue;
    private final ForgeConfigSpec.BooleanValue rollFullFallDamageImmunityValue;

    private final ForgeConfigSpec.BooleanValue slideEnabledValue;
    private final ForgeConfigSpec.IntValue slideHungerCostValue;
    private final ForgeConfigSpec.DoubleValue slideSpeedBoostMultiplierValue;
    private final ForgeConfigSpec.DoubleValue slideJumpMomentumMultiplierValue;
    private final ForgeConfigSpec.DoubleValue slideBoostSpeedCapValue;
    private final ForgeConfigSpec.IntValue slideDurationTicksValue;
    private final ForgeConfigSpec.IntValue slideCoolDownValue;

    private final ForgeConfigSpec.BooleanValue wallRunEnabledValue;
    private final ForgeConfigSpec.IntValue wallRunHungerCostValue;
    private final ForgeConfigSpec.DoubleValue wallRunMinimumSpeedValue;
    private final ForgeConfigSpec.DoubleValue wallRunEntrySpeedBoostValue;
    private final ForgeConfigSpec.DoubleValue wallRunBoostSpeedCapValue;
    private final ForgeConfigSpec.DoubleValue wallRunSpeedDecayPerTickValue;
    private final ForgeConfigSpec.DoubleValue wallRunFallSpeedValue;
    private final ForgeConfigSpec.IntValue wallRunDurationTicksValue;
    private final ForgeConfigSpec.DoubleValue wallJumpBoostMultiplierValue;

    private final Snapshot snapshot;

    private static Snapshot pendingLegacyMigration;
    private static Path pendingLegacyPath;

    private FMConfig(ForgeConfigSpec.Builder builder) {
        snapshot = null;

        enableFastMoveValue = builder
                .comment(
                        "Enable or disable all FastMove movement features.",
                        "Original FastMove value: true."
                )
                .translation("text.config.fastmove.option.enableFastMove")
                .define("generalEnableFastMove", true);
        legacyMovementBoostsValue = builder
                .comment(
                        "Restore the original timing-dependent slide/dive hopping and unrestricted movement boosts.",
                        "Disabled by default so slightly mistimed jumps receive the same balanced momentum as frame-perfect jumps.",
                        "Original FastMove behaviour: true."
                )
                .translation("text.config.fastmove.option.legacyMovementBoosts")
                .define("generalLegacyMovementBoosts", false);
        legacyMomentumStrengthPercentValue = builder
                .comment(
                        "Controls how much extra speed from the original slide/dive momentum is retained when jumping out of a slide or roll.",
                        "Only used while Original Slide/Dive Momentum is enabled.",
                        "100% keeps the current/original strength; 50% keeps half; 0% removes the extra chained momentum; values above 100% amplify it.",
                        "Range: 0-200%."
                )
                .translation("text.config.fastmove.option.legacyMomentumStrengthPercent")
                .defineInRange("generalLegacyMomentumStrengthPercent", 100, 0, 200);

        slideEnabledValue = builder
                .comment(
                        "Enable sliding. Original FastMove value: true."
                )
                .translation("text.config.fastmove.option.slideEnabled")
                .define("slideEnabled", true);
        slideHungerCostValue = builder
                .comment(
                        "Extra hunger used each time a slide starts.",
                        "Minecraft drains hidden saturation first, so the visible hunger bar may not change immediately.",
                        "Once saturation is empty, roughly 4 cost removes half of one hunger icon.",
                        "Recommended values: 0-2. Range: 0-10."
                )
                .translation("text.config.fastmove.option.slideHungerCost")
                .defineInRange("slideHungerCost", 1, 0, 10);
        slideSpeedBoostMultiplierValue = builder
                .comment(
                        "Multiplier for the slide movement boost itself.",
                        "The balanced momentum system lets the ordinary slide remain fast.",
                        "Original FastMove value: 1.0."
                )
                .translation("text.config.fastmove.option.slideSpeedBoostMultiplier")
                .defineInRange("slideSpeedBoostMultiplier", 1.0D, 0.0D, 10.0D);
        slideJumpMomentumMultiplierValue = builder
                .comment(
                        "Extra horizontal momentum retained when jumping out of a slide.",
                        "Only used while Original Slide/Dive Momentum is disabled.",
                        "Balanced default: 0.90. The boost is capped so repeated slide-hopping cannot stack speed forever."
                )
                .translation("text.config.fastmove.option.slideJumpMomentumMultiplier")
                .defineInRange("slideJumpMomentumMultiplier", 0.90D, 0.0D, 5.0D);
        slideBoostSpeedCapValue = builder
                .comment(
                        "Maximum horizontal speed that the balanced slide entry/jump boosts will build toward.",
                        "Players already moving faster than this keep their speed, but FastMove will not add more boost.",
                        "This prevents run-slide-jump chains from accelerating forever."
                )
                .translation("text.config.fastmove.option.slideBoostSpeedCap")
                .defineInRange("slideBoostSpeedCap", 0.50D, 0.05D, 3.0D);
        slideDurationTicksValue = builder
                .comment(
                        "How long a balanced slide takes to ease from its entry speed into the held crawl, in ticks.",
                        "20 ticks are normally one second. Default: 40 ticks (about 2 seconds).",
                        "The slowdown is intentionally gentle at first and stronger near the end instead of using exponential friction."
                )
                .translation("text.config.fastmove.option.slideDurationTicks")
                .defineInRange("slideDurationTicks", 40, 10, 200);
        slideCoolDownValue = builder
                .comment(
                        "Cooldown after starting a slide, measured in ticks.",
                        "20 ticks are normally one second. Original FastMove value: 0."
                )
                .translation("text.config.fastmove.option.slideCoolDown")
                .defineInRange("slideCooldownTicks", 0, 0, 1200);

        preferSlideNearGroundValue = builder
                .comment(
                        "When the down key is pressed shortly before landing, buffer a slide instead of starting a dive roll.",
                        "This prevents a mistimed slide-hop from turning into an unwanted roll."
                )
                .translation("text.config.fastmove.option.preferSlideNearGround")
                .define("preferSlideWhenNearGround", true);
        nearGroundSlideDistanceValue = builder
                .comment("Maximum distance below the player's feet that counts as near ground, in blocks.")
                .translation("text.config.fastmove.option.nearGroundSlideDistance")
                .defineInRange("nearGroundSlideDistance", 0.75D, 0.1D, 3.0D);
        slideInputBufferTicksValue = builder
                .comment("How long a near-ground slide input is remembered after it is pressed, in ticks.")
                .translation("text.config.fastmove.option.slideInputBufferTicks")
                .defineInRange("slideInputBufferTicks", 4, 1, 20);
        diveRollEnabledValue = builder
                .comment(
                        "Enable diving and rolling.",
                        "Continue holding the down key after a roll to remain crawling until the key is released.",
                        "Original FastMove value: true."
                )
                .translation("text.config.fastmove.option.diveRollEnabled")
                .define("diveRollEnabled", true);
        diveRollHungerCostValue = builder
                .comment(
                        "Extra hunger used each time a dive roll starts.",
                        "Minecraft drains hidden saturation first, so the visible hunger bar may not change immediately.",
                        "Once saturation is empty, roughly 4 cost removes half of one hunger icon.",
                        "Recommended values: 0-2. Range: 0-10."
                )
                .translation("text.config.fastmove.option.diveRollHungerCost")
                .defineInRange("diveRollHungerCost", 1, 0, 10);
        diveRollSpeedBoostMultiplierValue = builder
                .comment(
                        "Multiplier for the dive/roll movement boost itself.",
                        "Balanced default: 0.5. Original FastMove value: 1.0."
                )
                .translation("text.config.fastmove.option.diveRollSpeedBoostMultiplier")
                .defineInRange("diveRollSpeedBoostMultiplier", 0.5D, 0.0D, 10.0D);
        diveRollJumpMomentumMultiplierValue = builder
                .comment(
                        "Extra horizontal momentum retained when jumping out of a roll.",
                        "Only used while Legacy Movement Boosts is disabled.",
                        "Balanced default: 0.5. Original unrestricted behaviour is available through the legacy toggle."
                )
                .translation("text.config.fastmove.option.diveRollJumpMomentumMultiplier")
                .defineInRange("diveRollJumpMomentumMultiplier", 0.5D, 0.0D, 5.0D);
        diveRollCoolDownValue = builder
                .comment(
                        "Cooldown after starting a dive roll, measured in ticks.",
                        "20 ticks are normally one second. Original FastMove value: 0."
                )
                .translation("text.config.fastmove.option.diveRollCoolDown")
                .defineInRange("diveRollCooldownTicks", 0, 0, 1200);
        diveRollWhenSwimmingValue = builder
                .comment("Allow dive rolls while in water. Original FastMove value: false.")
                .translation("text.config.fastmove.option.diveRollWhenSwimming")
                .define("diveRollEnabledWhileSwimming", false);
        diveRollWhenFlyingValue = builder
                .comment("Allow dive rolls while fall-flying with an elytra. Original FastMove value: false.")
                .translation("text.config.fastmove.option.diveRollWhenFlying")
                .define("diveRollEnabledWhileFallFlying", false);
        rollDurationTicksValue = builder
                .comment("Length of the rolling animation/state in ticks. Original FastMove value: 10.")
                .translation("text.config.fastmove.option.rollDurationTicks")
                .defineInRange("rollDurationTicks", 10, 1, 60);
        rollFallProtectionWindowTicksValue = builder
                .comment(
                        "Fall protection only applies during the first part of a roll, measured in ticks.",
                        "Balanced default: 5. Original FastMove effectively protected the full 10-tick roll."
                )
                .translation("text.config.fastmove.option.rollFallProtectionWindowTicks")
                .defineInRange("rollFallProtectionWindowTicks", 5, 0, 60);
        rollFallDamageMultiplierValue = builder
                .comment(
                        "Remaining fall damage after a successful roll.",
                        "0.35 means 35% damage remains. Set to 0 for full reduction after the safe-distance bonus."
                )
                .translation("text.config.fastmove.option.rollFallDamageMultiplier")
                .defineInRange("rollFallDamageMultiplier", 0.35D, 0.0D, 1.0D);
        rollAdditionalSafeFallDistanceValue = builder
                .comment("Flat amount subtracted from incoming fall damage before the multiplier is applied.")
                .translation("text.config.fastmove.option.rollAdditionalSafeFallDistance")
                .defineInRange("rollAdditionalSafeFallDistance", 3.0D, 0.0D, 100.0D);
        rollFullFallDamageImmunityValue = builder
                .comment(
                        "Restore the original complete fall-damage immunity while the protection window is active.",
                        "Disabled by default so water-bucket and other fall clutches remain useful."
                )
                .translation("text.config.fastmove.option.rollFullFallDamageImmunity")
                .define("rollFullFallDamageImmunity", false);

        wallRunEnabledValue = builder
                .comment(
                        "Enable wall-running and wall-jumping. Original FastMove value: true."
                )
                .translation("text.config.fastmove.option.wallRunEnabled")
                .define("wallRunEnabled", true);
        wallRunHungerCostValue = builder
                .comment(
                        "Extra hunger used each time a wall run starts.",
                        "Minecraft drains hidden saturation first, so the visible hunger bar may not change immediately.",
                        "Once saturation is empty, roughly 4 cost removes half of one hunger icon.",
                        "Recommended values: 0-2. Range: 0-10."
                )
                .translation("text.config.fastmove.option.wallRunHungerCost")
                .defineInRange("wallRunHungerCost", 1, 0, 10);
        wallRunMinimumSpeedValue = builder
                .comment(
                        "Minimum horizontal X/Z speed required to start and continue wall-running.",
                        "Vertical jump/fall speed never counts. The player must also have real movement along the wall.",
                        "This prevents starting a wall run by simply jumping or falling beside a wall."
                )
                .translation("text.config.fastmove.option.wallRunMinimumSpeed")
                .defineInRange("wallRunMinimumSpeed", 0.19D, 0.0D, 2.0D);
        wallRunEntrySpeedBoostValue = builder
                .comment(
                        "Small horizontal speed boost added when entering a wall run.",
                        "The player's existing speed along the wall is preserved first; this is added on top until the boost cap is reached."
                )
                .translation("text.config.fastmove.option.wallRunEntrySpeedBoost")
                .defineInRange("wallRunEntrySpeedBoost", 0.08D, 0.0D, 1.0D);
        wallRunBoostSpeedCapValue = builder
                .comment(
                        "Speed threshold for receiving wall-run entry and wall-jump forward boosts.",
                        "This is not a hard speed limit: entering faster than this preserves the higher speed, but no extra boost is added."
                )
                .translation("text.config.fastmove.option.wallRunBoostSpeedCap")
                .defineInRange("wallRunBoostSpeedCap", 0.40D, 0.01D, 3.0D);
        wallRunSpeedDecayPerTickValue = builder
                .comment(
                        "Horizontal wall-run speed lost each tick.",
                        "When speed falls below Minimum Start Speed, the player drops from the wall run."
                )
                .translation("text.config.fastmove.option.wallRunSpeedDecayPerTick")
                .defineInRange("wallRunSpeedDecayPerTick", 0.0037D, 0.0D, 0.1D);
        wallRunFallSpeedValue = builder
                .comment("Maximum downward speed while wall-running, in blocks per tick.")
                .translation("text.config.fastmove.option.wallRunFallSpeed")
                .defineInRange("wallRunFallSpeed", 0.04D, 0.0D, 1.0D);
        wallRunDurationTicksValue = builder
                .comment(
                        "Maximum wall-run duration in ticks.",
                        "Unlike older versions, this is now a real duration limit. Original value: 60."
                )
                .translation("text.config.fastmove.option.wallRunDurationTicks")
                .defineInRange("wallRunDurationTicks", 100, 1, 1200);
        wallJumpBoostMultiplierValue = builder
                .comment(
                        "Multiplier for the wall-jump launch velocity.",
                        "Original FastMove value: 1.0."
                )
                .translation("text.config.fastmove.option.wallJumpBoostMultiplier")
                .defineInRange("wallJumpBoostMultiplier", 1.0D, 0.0D, 10.0D);
    }

    private FMConfig(Snapshot snapshot) {
        this.snapshot = snapshot;
        enableFastMoveValue = null;
        legacyMovementBoostsValue = null;
        legacyMomentumStrengthPercentValue = null;
        diveRollEnabledValue = null;
        diveRollHungerCostValue = null;
        diveRollSpeedBoostMultiplierValue = null;
        diveRollJumpMomentumMultiplierValue = null;
        diveRollCoolDownValue = null;
        diveRollWhenSwimmingValue = null;
        diveRollWhenFlyingValue = null;
        preferSlideNearGroundValue = null;
        nearGroundSlideDistanceValue = null;
        slideInputBufferTicksValue = null;
        rollDurationTicksValue = null;
        rollFallProtectionWindowTicksValue = null;
        rollFallDamageMultiplierValue = null;
        rollAdditionalSafeFallDistanceValue = null;
        rollFullFallDamageImmunityValue = null;
        slideEnabledValue = null;
        slideHungerCostValue = null;
        slideSpeedBoostMultiplierValue = null;
        slideJumpMomentumMultiplierValue = null;
        slideBoostSpeedCapValue = null;
        slideDurationTicksValue = null;
        slideCoolDownValue = null;
        wallRunEnabledValue = null;
        wallRunHungerCostValue = null;
        wallRunMinimumSpeedValue = null;
        wallRunEntrySpeedBoostValue = null;
        wallRunBoostSpeedCapValue = null;
        wallRunSpeedDecayPerTickValue = null;
        wallRunFallSpeedValue = null;
        wallRunDurationTicksValue = null;
        wallJumpBoostMultiplierValue = null;
    }

    public static void prepareLegacyMigration() {
        Path configDirectory = FMLPaths.CONFIGDIR.get();
        Path legacyJsonPath = configDirectory.resolve(LEGACY_FILE_NAME);
        Path commonTomlPath = configDirectory.resolve(FILE_NAME);

        if (Files.isRegularFile(commonTomlPath)) {
            try {
                String toml = Files.readString(commonTomlPath);
                if (toml.contains("[general]") || toml.contains("[dive_roll]")
                        || toml.contains("[slide]") || toml.contains("[wall_run]")) {
                    pendingLegacyMigration = snapshotFromPass1AToml(toml);
                    Path archived = nextArchivePath(commonTomlPath, ".pass1a.migrated");
                    Files.move(commonTomlPath, archived, StandardCopyOption.REPLACE_EXISTING);
                    pendingLegacyPath = null;
                    LOGGER.info("Found the earlier FastMove v1.1.0 test config; it will be flattened into the new layout.");
                    return;
                }

                // The first flattened v1.1.0 test build used these two wall-run keys before the
                // entry-boost/decay model replaced them. Rebuild that draft config once so obsolete
                // fields do not linger in users' TOML files, while preserving all compatible values.
                if (toml.contains("wallRunSpeedMultiplier") || toml.contains("wallRunMaximumSpeed")) {
                    pendingLegacyMigration = snapshotFromFlatTestToml(toml);
                    Path archived = nextArchivePath(commonTomlPath, ".draft.migrated");
                    Files.move(commonTomlPath, archived, StandardCopyOption.REPLACE_EXISTING);
                    pendingLegacyPath = null;
                    LOGGER.info("Found the earlier flattened FastMove v1.1.0 test config; compatible values will be migrated.");
                    return;
                }
            } catch (Exception exception) {
                LOGGER.warn("Could not inspect the existing FastMove common config; it will be loaded normally.", exception);
            }
        }

        if (!Files.isRegularFile(legacyJsonPath) || Files.exists(commonTomlPath)) return;
        try (Reader reader = Files.newBufferedReader(legacyJsonPath)) {
            pendingLegacyMigration = snapshotFromJson(JsonParser.parseReader(reader).getAsJsonObject());
            pendingLegacyPath = legacyJsonPath;
            LOGGER.info("Found legacy FastMove config; it will be migrated to {}.", FILE_NAME);
        } catch (Exception exception) {
            LOGGER.warn("Could not read legacy FastMove config {}; defaults will be used.", legacyJsonPath, exception);
        }
    }

    private static Snapshot snapshotFromPass1AToml(String toml) {
        JsonObject json = new JsonObject();
        String section = "";
        for (String rawLine : toml.split("\\R")) {
            String line = rawLine;
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment);
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) continue;
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            String networkKey = switch (section + "." + key) {
                case "general.enableFastMove" -> "enableFastMove";
                case "dive_roll.enabled" -> "diveRollEnabled";
                case "dive_roll.exhaustionCost" -> "diveRollExhaustionCost";
                case "dive_roll.speedBoostMultiplier" -> "diveRollSpeedBoostMultiplier";
                case "dive_roll.cooldownTicks" -> "diveRollCoolDown";
                case "dive_roll.enabledWhileSwimming" -> "diveRollWhenSwimming";
                case "dive_roll.enabledWhileFallFlying" -> "diveRollWhenFlying";
                case "slide.enabled" -> "slideEnabled";
                case "slide.exhaustionCost" -> "slideExhaustionCost";
                case "slide.speedBoostMultiplier" -> "slideSpeedBoostMultiplier";
                case "slide.cooldownTicks" -> "slideCoolDown";
                case "wall_run.enabled" -> "wallRunEnabled";
                case "wall_run.exhaustionCost" -> "wallRunExhaustionCost";
                case "wall_run.wallJumpBoostMultiplier" -> "wallJumpBoostMultiplier";
                case "wall_run.durationTicks" -> "wallRunDurationTicks";
                default -> null;
            };
            if (networkKey == null) continue;
            try {
                if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                    json.addProperty(networkKey, Boolean.parseBoolean(value));
                } else {
                    json.addProperty(networkKey, Double.parseDouble(value));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return snapshotFromJson(json);
    }

    private static Snapshot snapshotFromFlatTestToml(String toml) {
        JsonObject json = new JsonObject();
        for (String rawLine : toml.split("\\R")) {
            String line = rawLine;
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment);
            line = line.trim();
            if (line.isEmpty() || line.startsWith("[")) continue;

            int equals = line.indexOf('=');
            if (equals < 0) continue;
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            String networkKey = switch (key) {
                case "generalEnableFastMove" -> "enableFastMove";
                case "generalLegacyMovementBoosts" -> "legacyMovementBoosts";
                case "generalLegacyMomentumStrengthPercent" -> "legacyMomentumStrengthPercent";
                case "diveRollCrawlEnabled" -> "diveRollEnabled";
                case "diveRollCooldownTicks" -> "diveRollCoolDown";
                case "diveRollEnabledWhileSwimming" -> "diveRollWhenSwimming";
                case "diveRollEnabledWhileFallFlying" -> "diveRollWhenFlying";
                case "slideCooldownTicks" -> "slideCoolDown";
                case "wallRunMaximumSpeed" -> "wallRunBoostSpeedCap";
                case "wallRunSpeedMultiplier" -> null; // superseded by preserved speed + entry boost/decay
                default -> key;
            };
            if (networkKey == null) continue;

            try {
                if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                    json.addProperty(networkKey, Boolean.parseBoolean(value));
                } else {
                    json.addProperty(networkKey, Double.parseDouble(value));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return snapshotFromJson(json);
    }

    public static void onConfigLoading(ModConfigEvent.Loading event) {
        ModConfig modConfig = event.getConfig();
        if (modConfig.getSpec() != SPEC || pendingLegacyMigration == null) return;

        try {
            applySnapshotToLocal(pendingLegacyMigration);
            SPEC.save();
            archiveLegacyFile(pendingLegacyPath);
            LOGGER.info("Applied migrated FastMove settings to {}.", FILE_NAME);
        } catch (Exception exception) {
            LOGGER.error("Failed to finish FastMove legacy config migration.", exception);
        } finally {
            pendingLegacyMigration = null;
            pendingLegacyPath = null;
        }
    }

    private static void applySnapshotToLocal(Snapshot v) {
        LOCAL.enableFastMoveValue.set(v.enableFastMove);
        LOCAL.legacyMovementBoostsValue.set(v.legacyMovementBoosts);
        LOCAL.legacyMomentumStrengthPercentValue.set(v.legacyMomentumStrengthPercent);
        LOCAL.diveRollEnabledValue.set(v.diveRollEnabled);
        LOCAL.diveRollHungerCostValue.set(v.diveRollHungerCost);
        LOCAL.diveRollSpeedBoostMultiplierValue.set(v.diveRollSpeedBoostMultiplier);
        LOCAL.diveRollJumpMomentumMultiplierValue.set(v.diveRollJumpMomentumMultiplier);
        LOCAL.diveRollCoolDownValue.set(v.diveRollCoolDown);
        LOCAL.diveRollWhenSwimmingValue.set(v.diveRollWhenSwimming);
        LOCAL.diveRollWhenFlyingValue.set(v.diveRollWhenFlying);
        LOCAL.preferSlideNearGroundValue.set(v.preferSlideNearGround);
        LOCAL.nearGroundSlideDistanceValue.set(v.nearGroundSlideDistance);
        LOCAL.slideInputBufferTicksValue.set(v.slideInputBufferTicks);
        LOCAL.rollDurationTicksValue.set(v.rollDurationTicks);
        LOCAL.rollFallProtectionWindowTicksValue.set(v.rollFallProtectionWindowTicks);
        LOCAL.rollFallDamageMultiplierValue.set(v.rollFallDamageMultiplier);
        LOCAL.rollAdditionalSafeFallDistanceValue.set(v.rollAdditionalSafeFallDistance);
        LOCAL.rollFullFallDamageImmunityValue.set(v.rollFullFallDamageImmunity);
        LOCAL.slideEnabledValue.set(v.slideEnabled);
        LOCAL.slideHungerCostValue.set(v.slideHungerCost);
        LOCAL.slideSpeedBoostMultiplierValue.set(v.slideSpeedBoostMultiplier);
        LOCAL.slideJumpMomentumMultiplierValue.set(v.slideJumpMomentumMultiplier);
        LOCAL.slideBoostSpeedCapValue.set(v.slideBoostSpeedCap);
        LOCAL.slideDurationTicksValue.set(v.slideDurationTicks);
        LOCAL.slideCoolDownValue.set(v.slideCoolDown);
        LOCAL.wallRunEnabledValue.set(v.wallRunEnabled);
        LOCAL.wallRunHungerCostValue.set(v.wallRunHungerCost);
        LOCAL.wallRunMinimumSpeedValue.set(v.wallRunMinimumSpeed);
        LOCAL.wallRunEntrySpeedBoostValue.set(v.wallRunEntrySpeedBoost);
        LOCAL.wallRunBoostSpeedCapValue.set(v.wallRunBoostSpeedCap);
        LOCAL.wallRunSpeedDecayPerTickValue.set(v.wallRunSpeedDecayPerTick);
        LOCAL.wallRunFallSpeedValue.set(v.wallRunFallSpeed);
        LOCAL.wallRunDurationTicksValue.set(v.wallRunDurationTicks);
        LOCAL.wallJumpBoostMultiplierValue.set(v.wallJumpBoostMultiplier);
    }

    private static void archiveLegacyFile(Path legacyPath) throws Exception {
        if (legacyPath == null || !Files.exists(legacyPath)) return;
        Files.move(legacyPath, nextArchivePath(legacyPath, ".migrated"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path nextArchivePath(Path source, String suffix) {
        Path archivedPath = source.resolveSibling(source.getFileName() + suffix);
        if (!Files.exists(archivedPath)) return archivedPath;
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return source.resolveSibling(source.getFileName() + suffix + "-" + timestamp);
    }

    public static FMConfig disabledConfig() {
        Snapshot d = defaultSnapshot();
        return new FMConfig(new Snapshot(
                false, d.legacyMovementBoosts, d.legacyMomentumStrengthPercent,
                d.diveRollEnabled, d.diveRollHungerCost, d.diveRollSpeedBoostMultiplier, d.diveRollJumpMomentumMultiplier,
                d.diveRollCoolDown, d.diveRollWhenSwimming, d.diveRollWhenFlying, d.preferSlideNearGround,
                d.nearGroundSlideDistance, d.slideInputBufferTicks, d.rollDurationTicks, d.rollFallProtectionWindowTicks,
                d.rollFallDamageMultiplier, d.rollAdditionalSafeFallDistance, d.rollFullFallDamageImmunity,
                d.slideEnabled, d.slideHungerCost, d.slideSpeedBoostMultiplier, d.slideJumpMomentumMultiplier, d.slideBoostSpeedCap, d.slideDurationTicks, d.slideCoolDown,
                d.wallRunEnabled, d.wallRunHungerCost, d.wallRunMinimumSpeed, d.wallRunEntrySpeedBoost,
                d.wallRunBoostSpeedCap, d.wallRunSpeedDecayPerTick, d.wallRunFallSpeed, d.wallRunDurationTicks, d.wallJumpBoostMultiplier
        ));
    }

    public String toNetworkJson() {
        JsonObject json = new JsonObject();
        json.addProperty("configVersion", 7);
        json.addProperty("enableFastMove", enableFastMove());
        json.addProperty("legacyMovementBoosts", legacyMovementBoosts());
        json.addProperty("legacyMomentumStrengthPercent", legacyMomentumStrengthPercent());
        json.addProperty("diveRollEnabled", diveRollEnabled());
        json.addProperty("diveRollHungerCost", diveRollHungerCost());
        json.addProperty("diveRollSpeedBoostMultiplier", diveRollSpeedBoostMultiplier());
        json.addProperty("diveRollJumpMomentumMultiplier", diveRollJumpMomentumMultiplier());
        json.addProperty("diveRollCoolDown", diveRollCoolDown());
        json.addProperty("diveRollWhenSwimming", diveRollWhenSwimming());
        json.addProperty("diveRollWhenFlying", diveRollWhenFlying());
        json.addProperty("preferSlideNearGround", preferSlideNearGround());
        json.addProperty("nearGroundSlideDistance", nearGroundSlideDistance());
        json.addProperty("slideInputBufferTicks", slideInputBufferTicks());
        json.addProperty("rollDurationTicks", rollDurationTicks());
        json.addProperty("rollFallProtectionWindowTicks", rollFallProtectionWindowTicks());
        json.addProperty("rollFallDamageMultiplier", rollFallDamageMultiplier());
        json.addProperty("rollAdditionalSafeFallDistance", rollAdditionalSafeFallDistance());
        json.addProperty("rollFullFallDamageImmunity", rollFullFallDamageImmunity());
        json.addProperty("slideEnabled", slideEnabled());
        json.addProperty("slideHungerCost", slideHungerCost());
        json.addProperty("slideSpeedBoostMultiplier", slideSpeedBoostMultiplier());
        json.addProperty("slideJumpMomentumMultiplier", slideJumpMomentumMultiplier());
        json.addProperty("slideBoostSpeedCap", slideBoostSpeedCap());
        json.addProperty("slideDurationTicks", slideDurationTicks());
        json.addProperty("slideCoolDown", slideCoolDown());
        json.addProperty("wallRunEnabled", wallRunEnabled());
        json.addProperty("wallRunHungerCost", wallRunHungerCost());
        json.addProperty("wallRunMinimumSpeed", wallRunMinimumSpeed());
        json.addProperty("wallRunEntrySpeedBoost", wallRunEntrySpeedBoost());
        json.addProperty("wallRunBoostSpeedCap", wallRunBoostSpeedCap());
        json.addProperty("wallRunSpeedDecayPerTick", wallRunSpeedDecayPerTick());
        json.addProperty("wallRunFallSpeed", wallRunFallSpeed());
        json.addProperty("wallRunDurationTicks", wallRunDurationTicks());
        json.addProperty("wallJumpBoostMultiplier", wallJumpBoostMultiplier());
        return GSON.toJson(json);
    }

    public static FMConfig fromNetworkJson(String jsonString) {
        try {
            return new FMConfig(snapshotFromJson(JsonParser.parseString(jsonString).getAsJsonObject()));
        } catch (Exception exception) {
            LOGGER.warn("Received malformed FastMove config sync; using safe defaults.", exception);
            return new FMConfig(defaultSnapshot());
        }
    }

    private static Snapshot snapshotFromJson(JsonObject json) {
        Snapshot d = defaultSnapshot();
        return new Snapshot(
                getBool(json, "enableFastMove", d.enableFastMove),
                getBool(json, "legacyMovementBoosts", d.legacyMovementBoosts),
                clamp(getInt(json, d.legacyMomentumStrengthPercent, "legacyMomentumStrengthPercent"), 0, 200),
                getBool(json, "diveRollEnabled", d.diveRollEnabled),
                getHungerCost(json, d.diveRollHungerCost, "diveRollHungerCost", "diveRollExhaustionCost", "diveRollStaminaCost"),
                clamp(getDouble(json, d.diveRollSpeedBoostMultiplier, "diveRollSpeedBoostMultiplier"), 0.0D, 10.0D),
                clamp(getDouble(json, d.diveRollJumpMomentumMultiplier, "diveRollJumpMomentumMultiplier"), 0.0D, 5.0D),
                clamp(getInt(json, d.diveRollCoolDown, "diveRollCoolDown"), 0, 1200),
                getBool(json, "diveRollWhenSwimming", d.diveRollWhenSwimming),
                getBool(json, "diveRollWhenFlying", d.diveRollWhenFlying),
                getBool(json, "preferSlideNearGround", d.preferSlideNearGround),
                clamp(getDouble(json, d.nearGroundSlideDistance, "nearGroundSlideDistance"), 0.1D, 3.0D),
                clamp(getInt(json, d.slideInputBufferTicks, "slideInputBufferTicks"), 1, 20),
                clamp(getInt(json, d.rollDurationTicks, "rollDurationTicks"), 1, 60),
                clamp(getInt(json, d.rollFallProtectionWindowTicks, "rollFallProtectionWindowTicks"), 0, 60),
                clamp(getDouble(json, d.rollFallDamageMultiplier, "rollFallDamageMultiplier"), 0.0D, 1.0D),
                clamp(getDouble(json, d.rollAdditionalSafeFallDistance, "rollAdditionalSafeFallDistance"), 0.0D, 100.0D),
                getBool(json, "rollFullFallDamageImmunity", d.rollFullFallDamageImmunity),
                getBool(json, "slideEnabled", d.slideEnabled),
                getHungerCost(json, d.slideHungerCost, "slideHungerCost", "slideExhaustionCost", "slideStaminaCost"),
                clamp(getDouble(json, d.slideSpeedBoostMultiplier, "slideSpeedBoostMultiplier"), 0.0D, 10.0D),
                clamp(getDouble(json, d.slideJumpMomentumMultiplier, "slideJumpMomentumMultiplier"), 0.0D, 5.0D),
                clamp(getDouble(json, d.slideBoostSpeedCap, "slideBoostSpeedCap"), 0.05D, 3.0D),
                clamp(getInt(json, d.slideDurationTicks, "slideDurationTicks"), 10, 200),
                clamp(getInt(json, d.slideCoolDown, "slideCoolDown"), 0, 1200),
                getBool(json, "wallRunEnabled", d.wallRunEnabled),
                getHungerCost(json, d.wallRunHungerCost, "wallRunHungerCost", "wallRunExhaustionCost", "wallRunStaminaCost"),
                clamp(getDouble(json, d.wallRunMinimumSpeed, "wallRunMinimumSpeed"), 0.0D, 2.0D),
                clamp(getDouble(json, d.wallRunEntrySpeedBoost, "wallRunEntrySpeedBoost"), 0.0D, 1.0D),
                clamp(getDouble(json, d.wallRunBoostSpeedCap, "wallRunBoostSpeedCap", "wallRunMaximumSpeed"), 0.01D, 3.0D),
                clamp(getDouble(json, d.wallRunSpeedDecayPerTick, "wallRunSpeedDecayPerTick"), 0.0D, 0.1D),
                clamp(getDouble(json, d.wallRunFallSpeed, "wallRunFallSpeed"), 0.0D, 1.0D),
                clamp(getInt(json, d.wallRunDurationTicks, "wallRunDurationTicks"), 1, 1200),
                clamp(getDouble(json, d.wallJumpBoostMultiplier, "wallJumpBoostMultiplier", "wallRunSpeedBoostMultiplier"), 0.0D, 10.0D)
        );
    }

    private static Snapshot defaultSnapshot() {
        return new Snapshot(
                true, false, 100,
                true, 1, 0.5D, 0.5D, 0, false, false, true, 0.75D, 4,
                10, 5, 0.35D, 3.0D, false,
                true, 1, 1.0D, 0.90D, 0.50D, 40, 0,
                true, 1, 0.19D, 0.08D, 0.40D, 0.0037D, 0.04D, 100, 1.0D
        );
    }

    private static boolean getBool(JsonObject json, String key, boolean fallback) {
        try {
            return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsBoolean() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int getHungerCost(JsonObject json, int fallback, String hungerKey, String legacyExhaustionKey, String legacyStaminaKey) {
        // v1.1.0+ stores the small, player-facing 0-10 value directly.
        if (json.has(hungerKey)) {
            return clamp(getInt(json, fallback, hungerKey), 0, 10);
        }

        // Older FastMove configs exposed the raw exhaustion amount (defaults 10/20), which became
        // far too punishing once the server-authoritative hunger handling was fixed. Scale those
        // legacy values down during migration so the old 10/20 defaults both become a light cost of 1.
        int legacy = getInt(json, Integer.MIN_VALUE, legacyExhaustionKey, legacyStaminaKey);
        if (legacy != Integer.MIN_VALUE) {
            if (legacy <= 0) return 0;
            return clamp(Math.max(1, Math.round(legacy / 20.0F)), 0, 10);
        }
        return fallback;
    }

    private static int getInt(JsonObject json, int fallback, String... keys) {
        for (String key : keys) {
            try {
                if (json.has(key) && json.get(key).isJsonPrimitive()) return json.get(key).getAsInt();
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    private static double getDouble(JsonObject json, double fallback, String... keys) {
        for (String key : keys) {
            try {
                if (json.has(key) && json.get(key).isJsonPrimitive()) return json.get(key).getAsDouble();
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }

    public boolean enableFastMove() { return snapshot == null ? enableFastMoveValue.get() : snapshot.enableFastMove; }
    public boolean legacyMovementBoosts() { return snapshot == null ? legacyMovementBoostsValue.get() : snapshot.legacyMovementBoosts; }
    public int legacyMomentumStrengthPercent() { return snapshot == null ? legacyMomentumStrengthPercentValue.get() : snapshot.legacyMomentumStrengthPercent; }
    public double legacyMomentumMultiplier() { return legacyMomentumStrengthPercent() / 100.0D; }
    public boolean diveRollEnabled() { return snapshot == null ? diveRollEnabledValue.get() : snapshot.diveRollEnabled; }
    public int diveRollHungerCost() { return snapshot == null ? diveRollHungerCostValue.get() : snapshot.diveRollHungerCost; }
    public double diveRollSpeedBoostMultiplier() { return snapshot == null ? diveRollSpeedBoostMultiplierValue.get() : snapshot.diveRollSpeedBoostMultiplier; }
    public double diveRollJumpMomentumMultiplier() { return snapshot == null ? diveRollJumpMomentumMultiplierValue.get() : snapshot.diveRollJumpMomentumMultiplier; }
    public int diveRollCoolDown() { return snapshot == null ? diveRollCoolDownValue.get() : snapshot.diveRollCoolDown; }
    public boolean diveRollWhenSwimming() { return snapshot == null ? diveRollWhenSwimmingValue.get() : snapshot.diveRollWhenSwimming; }
    public boolean diveRollWhenFlying() { return snapshot == null ? diveRollWhenFlyingValue.get() : snapshot.diveRollWhenFlying; }
    public boolean preferSlideNearGround() { return snapshot == null ? preferSlideNearGroundValue.get() : snapshot.preferSlideNearGround; }
    public double nearGroundSlideDistance() { return snapshot == null ? nearGroundSlideDistanceValue.get() : snapshot.nearGroundSlideDistance; }
    public int slideInputBufferTicks() { return snapshot == null ? slideInputBufferTicksValue.get() : snapshot.slideInputBufferTicks; }
    public int rollDurationTicks() { return snapshot == null ? rollDurationTicksValue.get() : snapshot.rollDurationTicks; }
    public int rollFallProtectionWindowTicks() { return snapshot == null ? rollFallProtectionWindowTicksValue.get() : snapshot.rollFallProtectionWindowTicks; }
    public double rollFallDamageMultiplier() { return snapshot == null ? rollFallDamageMultiplierValue.get() : snapshot.rollFallDamageMultiplier; }
    public double rollAdditionalSafeFallDistance() { return snapshot == null ? rollAdditionalSafeFallDistanceValue.get() : snapshot.rollAdditionalSafeFallDistance; }
    public boolean rollFullFallDamageImmunity() { return snapshot == null ? rollFullFallDamageImmunityValue.get() : snapshot.rollFullFallDamageImmunity; }
    public boolean slideEnabled() { return snapshot == null ? slideEnabledValue.get() : snapshot.slideEnabled; }
    public int slideHungerCost() { return snapshot == null ? slideHungerCostValue.get() : snapshot.slideHungerCost; }
    public double slideSpeedBoostMultiplier() { return snapshot == null ? slideSpeedBoostMultiplierValue.get() : snapshot.slideSpeedBoostMultiplier; }
    public double slideJumpMomentumMultiplier() { return snapshot == null ? slideJumpMomentumMultiplierValue.get() : snapshot.slideJumpMomentumMultiplier; }
    public double slideBoostSpeedCap() { return snapshot == null ? slideBoostSpeedCapValue.get() : snapshot.slideBoostSpeedCap; }
    public int slideDurationTicks() { return snapshot == null ? slideDurationTicksValue.get() : snapshot.slideDurationTicks; }
    public int slideCoolDown() { return snapshot == null ? slideCoolDownValue.get() : snapshot.slideCoolDown; }
    public boolean wallRunEnabled() { return snapshot == null ? wallRunEnabledValue.get() : snapshot.wallRunEnabled; }
    public int wallRunHungerCost() { return snapshot == null ? wallRunHungerCostValue.get() : snapshot.wallRunHungerCost; }
    public double wallRunMinimumSpeed() { return snapshot == null ? wallRunMinimumSpeedValue.get() : snapshot.wallRunMinimumSpeed; }
    public double wallRunEntrySpeedBoost() { return snapshot == null ? wallRunEntrySpeedBoostValue.get() : snapshot.wallRunEntrySpeedBoost; }
    public double wallRunBoostSpeedCap() { return snapshot == null ? wallRunBoostSpeedCapValue.get() : snapshot.wallRunBoostSpeedCap; }
    public double wallRunSpeedDecayPerTick() { return snapshot == null ? wallRunSpeedDecayPerTickValue.get() : snapshot.wallRunSpeedDecayPerTick; }
    public double wallRunFallSpeed() { return snapshot == null ? wallRunFallSpeedValue.get() : snapshot.wallRunFallSpeed; }
    public int wallRunDurationTicks() { return snapshot == null ? wallRunDurationTicksValue.get() : snapshot.wallRunDurationTicks; }
    public double wallJumpBoostMultiplier() { return snapshot == null ? wallJumpBoostMultiplierValue.get() : snapshot.wallJumpBoostMultiplier; }

    private record Snapshot(
            boolean enableFastMove,
            boolean legacyMovementBoosts,
            int legacyMomentumStrengthPercent,
            boolean diveRollEnabled,
            int diveRollHungerCost,
            double diveRollSpeedBoostMultiplier,
            double diveRollJumpMomentumMultiplier,
            int diveRollCoolDown,
            boolean diveRollWhenSwimming,
            boolean diveRollWhenFlying,
            boolean preferSlideNearGround,
            double nearGroundSlideDistance,
            int slideInputBufferTicks,
            int rollDurationTicks,
            int rollFallProtectionWindowTicks,
            double rollFallDamageMultiplier,
            double rollAdditionalSafeFallDistance,
            boolean rollFullFallDamageImmunity,
            boolean slideEnabled,
            int slideHungerCost,
            double slideSpeedBoostMultiplier,
            double slideJumpMomentumMultiplier,
            double slideBoostSpeedCap,
            int slideDurationTicks,
            int slideCoolDown,
            boolean wallRunEnabled,
            int wallRunHungerCost,
            double wallRunMinimumSpeed,
            double wallRunEntrySpeedBoost,
            double wallRunBoostSpeedCap,
            double wallRunSpeedDecayPerTick,
            double wallRunFallSpeed,
            int wallRunDurationTicks,
            double wallJumpBoostMultiplier
    ) {
    }
}
