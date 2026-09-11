package io.github.beeebea.fastmove.client;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import io.github.beeebea.fastmove.config.FMClientConfig;
import io.github.beeebea.fastmove.config.FMConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps NeoForge's native config widgets, saving, validation, undo and reset behaviour,
 * but lays FastMove's flat TOML values out under readable in-page section headings.
 */
public final class FastMoveConfigurationSectionScreen extends ConfigurationScreen.ConfigurationSectionScreen {
    private static final String[] GENERAL = {
            "generalEnableFastMove",
            "generalLegacyMovementBoosts",
            "generalLegacyMomentumStrengthPercent"
    };

    private static final String[] SLIDE = {
            "slideEnabled",
            "slideHungerCost",
            "slideSpeedBoostMultiplier",
            "slideJumpMomentumMultiplier",
            "slideBoostSpeedCap",
            "slideDurationTicks",
            "slideCooldownTicks",
            "preferSlideWhenNearGround",
            "nearGroundSlideDistance",
            "slideInputBufferTicks"
    };

    private static final String[] DIVE_ROLL = {
            "diveRollEnabled",
            "diveRollHungerCost",
            "diveRollSpeedBoostMultiplier",
            "diveRollJumpMomentumMultiplier",
            "diveRollCooldownTicks",
            "diveRollEnabledWhileSwimming",
            "diveRollEnabledWhileFallFlying",
            "rollDurationTicks",
            "rollFallProtectionWindowTicks",
            "rollFallDamageMultiplier",
            "rollAdditionalSafeFallDistance",
            "rollFullFallDamageImmunity"
    };

    private static final String[] WALL_RUN = {
            "wallRunEnabled",
            "wallRunHungerCost",
            "wallRunMinimumSpeed",
            "wallRunEntrySpeedBoost",
            "wallRunBoostSpeedCap",
            "wallRunSpeedDecayPerTick",
            "wallRunFallSpeed",
            "wallRunDurationTicks",
            "wallJumpBoostMultiplier"
    };

    private static final String[] WALL_RUN_CAMERA = {
            "cameraWallRunTiltEnabled",
            "cameraWallRunTiltDegrees",
            "cameraWallRunTiltSmoothing"
    };

    private static final String[] SLIDE_CAMERA = {
            "cameraSlideTiltEnabled",
            "cameraSlideTiltDegrees",
            "cameraSlideTiltSmoothing"
    };

    private static final String[] SLIDE_SOUND = {
            "slideSoundVolumePercent"
    };

    private static final String[] DIVE_ROLL_CAMERA = {
            "cameraDiveRollFullRollEnabled"
    };

    public FastMoveConfigurationSectionScreen(Screen parent, ModConfig.Type type, ModConfig modConfig, Component title) {
        super(parent, type, modConfig, title);
    }

    @Override
    protected ConfigurationScreen.ConfigurationSectionScreen rebuild() {
        if (list == null) return this;

        if (context.modSpec() != FMConfig.SPEC && context.modSpec() != FMClientConfig.SPEC) {
            return super.rebuild();
        }

        list.children().clear();
        Map<String, ModConfigSpec.ConfigValue<?>> values = new HashMap<>();
        for (UnmodifiableConfig.Entry entry : context.entries()) {
            if (entry.getRawValue() instanceof ModConfigSpec.ConfigValue<?> value) {
                values.put(entry.getKey(), value);
            }
        }

        boolean addedAny = false;
        if (context.modSpec() == FMConfig.SPEC) {
            addedAny |= addSection("text.config.fastmove.section.general", GENERAL, values);
            addedAny |= addSection("text.config.fastmove.section.slide", SLIDE, values);
            addedAny |= addSection("text.config.fastmove.section.diveRoll", DIVE_ROLL, values);
            addedAny |= addSection("text.config.fastmove.section.wallRun", WALL_RUN, values);
        } else {
            addedAny |= addSection("text.config.fastmove.section.wallRunCamera", WALL_RUN_CAMERA, values);
            addedAny |= addSection("text.config.fastmove.section.slideCamera", SLIDE_CAMERA, values);
            addedAny |= addSection("text.config.fastmove.section.slideSound", SLIDE_SOUND, values);
            addedAny |= addSection("text.config.fastmove.section.diveRollCamera", DIVE_ROLL_CAMERA, values);
        }

        if (addedAny && undoButton == null) {
            createUndoButton();
            createResetButton();
        }
        return this;
    }

    private boolean addSection(String translationKey, String[] keys, Map<String, ModConfigSpec.ConfigValue<?>> values) {
        boolean hasValues = false;
        for (String key : keys) {
            if (values.containsKey(key)) {
                hasValues = true;
                break;
            }
        }
        if (!hasValues) return false;

        StringWidget heading = new StringWidget(
                ConfigurationScreen.BIG_BUTTON_WIDTH,
                Button.DEFAULT_HEIGHT,
                Component.translatable(translationKey).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                font
        ).alignCenter();
        list.addSmall(heading, null);

        for (String key : keys) {
            ModConfigSpec.ConfigValue<?> value = values.get(key);
            if (value != null) addValue(key, value);
        }
        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addValue(String key, ModConfigSpec.ConfigValue<?> value) {
        ModConfigSpec.ValueSpec spec = getValueSpec(key);
        if (spec == null) return;

        Element element;
        if (value instanceof ModConfigSpec.BooleanValue booleanValue) {
            element = createBooleanValue(key, spec, booleanValue::getRaw, booleanValue::set);
        } else if (value instanceof ModConfigSpec.IntValue intValue) {
            element = createIntegerValue(key, spec, intValue::getRaw, intValue::set);
        } else if (value instanceof ModConfigSpec.LongValue longValue) {
            element = createLongValue(key, spec, longValue::getRaw, longValue::set);
        } else if (value instanceof ModConfigSpec.DoubleValue doubleValue) {
            element = createDoubleValue(key, spec, doubleValue::getRaw, doubleValue::set);
        } else {
            element = createOtherValue(key, value);
        }

        if (element == null) return;
        if (element.name() == null) {
            list.addSmall(new StringWidget(Button.DEFAULT_WIDTH, Button.DEFAULT_HEIGHT, Component.empty(), font), element.getWidget(options));
        } else {
            StringWidget label = new StringWidget(Button.DEFAULT_WIDTH, Button.DEFAULT_HEIGHT, element.name(), font).alignLeft();
            if (element.tooltip() != null) label.setTooltip(Tooltip.create(element.tooltip()));
            list.addSmall(label, element.getWidget(options));
        }
    }
}
