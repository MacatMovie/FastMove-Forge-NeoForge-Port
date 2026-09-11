package io.github.beeebea.fastmove.client;

import io.github.beeebea.fastmove.FastMove;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Flat, scrolling FastMove config page with Minecraft-style section headings. */
public final class FastMoveConfigSectionScreen extends Screen {
    private static final Section[] GAMEPLAY_SECTIONS = {
            new Section("text.config.fastmove.section.general", new String[]{
                    "generalEnableFastMove",
                    "generalLegacyMovementBoosts",
                    "generalLegacyMomentumStrengthPercent"
            }),
            new Section("text.config.fastmove.section.slide", new String[]{
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
            }),
            new Section("text.config.fastmove.section.diveRoll", new String[]{
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
            }),
            new Section("text.config.fastmove.section.wallRun", new String[]{
                    "wallRunEnabled",
                    "wallRunHungerCost",
                    "wallRunMinimumSpeed",
                    "wallRunEntrySpeedBoost",
                    "wallRunBoostSpeedCap",
                    "wallRunSpeedDecayPerTick",
                    "wallRunFallSpeed",
                    "wallRunDurationTicks",
                    "wallJumpBoostMultiplier"
            })
    };

    private static final Section[] CLIENT_SECTIONS = {
            new Section("text.config.fastmove.section.wallRunCamera", new String[]{
                    "cameraWallRunTiltEnabled",
                    "cameraWallRunTiltDegrees",
                    "cameraWallRunTiltSmoothing"
            }),
            new Section("text.config.fastmove.section.slideCamera", new String[]{
                    "cameraSlideTiltEnabled",
                    "cameraSlideTiltDegrees",
                    "cameraSlideTiltSmoothing"
            }),
            new Section("text.config.fastmove.section.slideSound", new String[]{
                    "slideSoundVolumePercent"
            }),
            new Section("text.config.fastmove.section.diveRollCamera", new String[]{
                    "cameraDiveRollFullRollEnabled"
            })
    };

    private final Screen parent;
    private final ForgeConfigSpec spec;
    private final Section[] sections;
    private final boolean gameplay;
    private ConfigList list;

    private FastMoveConfigSectionScreen(Screen parent, Component title, ForgeConfigSpec spec, Section[] sections, boolean gameplay) {
        super(title);
        this.parent = parent;
        this.spec = spec;
        this.sections = sections;
        this.gameplay = gameplay;
    }

    public static FastMoveConfigSectionScreen gameplay(Screen parent, ForgeConfigSpec spec) {
        return new FastMoveConfigSectionScreen(
                parent,
                Component.translatable("text.config.fastmove.common"),
                spec,
                GAMEPLAY_SECTIONS,
                true
        );
    }

    public static FastMoveConfigSectionScreen client(Screen parent, ForgeConfigSpec spec) {
        return new FastMoveConfigSectionScreen(
                parent,
                Component.translatable("text.config.fastmove.client"),
                spec,
                CLIENT_SECTIONS,
                false
        );
    }

    @Override
    protected void init() {
        list = new ConfigList(minecraft, width, height, 32, height - 36, 26);
        addRenderableWidget(list);

        for (Section section : sections) {
            if (!hasAnyValue(section)) continue;
            list.addConfigEntry(new HeaderEntry(Component.translatable(section.translationKey)));
            for (String key : section.keys) addValueEntry(key);
        }

        addRenderableWidget(Button.builder(
                Component.translatable("controls.reset"),
                button -> resetAll()
        ).bounds(width / 2 - 154, height - 28, 150, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.done"),
                button -> saveAndClose()
        ).bounds(width / 2 + 4, height - 28, 150, 20).build());
    }

    private boolean hasAnyValue(Section section) {
        for (String key : section.keys) {
            if (spec.getValues().getRaw(key) instanceof ForgeConfigSpec.ConfigValue<?>) return true;
        }
        return false;
    }

    private void addValueEntry(String key) {
        Object rawValue = spec.getValues().getRaw(key);
        Object rawSpec = spec.getSpec().getRaw(key);
        if (!(rawValue instanceof ForgeConfigSpec.ConfigValue<?> value)
                || !(rawSpec instanceof ForgeConfigSpec.ValueSpec valueSpec)) return;

        Object current;
        try {
            current = value.get();
        } catch (Exception ignored) {
            current = value.getDefault();
        }

        Component label = valueSpec.getTranslationKey() != null
                ? Component.translatable(valueSpec.getTranslationKey())
                : Component.literal(key);
        Component tooltip = cleanTooltip(valueSpec.getComment());

        if (current instanceof Boolean booleanValue) {
            list.addConfigEntry(new BooleanEntry(label, tooltip, value, valueSpec, booleanValue));
        } else if (key.equals("generalLegacyMomentumStrengthPercent") && current instanceof Integer integerValue) {
            list.addConfigEntry(new MomentumStrengthSliderEntry(label, tooltip, value, valueSpec, integerValue));
        } else if (key.equals("slideSoundVolumePercent") && current instanceof Integer integerValue) {
            list.addConfigEntry(new SoundVolumeSliderEntry(label, tooltip, value, valueSpec, integerValue));
        } else if (current instanceof Integer || current instanceof Long || current instanceof Double || current instanceof Float) {
            list.addConfigEntry(new NumberEntry(label, tooltip, value, valueSpec, current));
        }
    }

    private Component cleanTooltip(String comment) {
        if (comment == null || comment.isBlank()) return null;
        StringBuilder cleaned = new StringBuilder();
        for (String line : comment.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("====")) continue;
            if (cleaned.length() > 0) cleaned.append('\n');
            cleaned.append(trimmed);
        }
        return cleaned.length() == 0 ? null : Component.literal(cleaned.toString());
    }

    private void resetAll() {
        for (ConfigEntry entry : list.children()) entry.resetToDefault();
    }

    private void saveAndClose() {
        for (ConfigEntry entry : list.children()) entry.commit();
        spec.save();
        if (gameplay) FastMove.syncGameplayConfigToConnectedPlayers();
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
    }

    private record Section(String translationKey, String[] keys) {
    }

    private final class ConfigList extends ContainerObjectSelectionList<ConfigEntry> {
        ConfigList(Minecraft minecraft, int width, int height, int top, int bottom, int rowHeight) {
            super(minecraft, width, height, top, bottom, rowHeight);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
        }

        void addConfigEntry(ConfigEntry entry) {
            addEntry(entry);
        }

        @Override
        public int getRowWidth() {
            return Math.min(620, Math.max(260, width - 60));
        }

        @Override
        protected int getScrollbarPosition() {
            return width / 2 + getRowWidth() / 2 + 6;
        }
    }

    private abstract class ConfigEntry extends ContainerObjectSelectionList.Entry<ConfigEntry> {
        abstract void commit();
        abstract void resetToDefault();
    }

    private final class HeaderEntry extends ConfigEntry {
        private final Component heading;

        HeaderEntry(Component heading) {
            this.heading = heading.copy().withStyle(style -> style.withBold(true));
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            graphics.drawCenteredString(font, heading, left + width / 2, top + 8, 0xFFFF55);
        }

        @Override void commit() { }
        @Override void resetToDefault() { }
        @Override public List<? extends GuiEventListener> children() { return Collections.emptyList(); }
        @Override public List<? extends NarratableEntry> narratables() { return Collections.emptyList(); }
    }

    private abstract class ValueEntry extends ConfigEntry {
        final Component label;
        final Component tooltip;
        final ForgeConfigSpec.ConfigValue<?> value;
        final ForgeConfigSpec.ValueSpec valueSpec;
        AbstractWidget widget;
        private final List<AbstractWidget> widgets = new ArrayList<>(1);

        ValueEntry(Component label, Component tooltip, ForgeConfigSpec.ConfigValue<?> value,
                   ForgeConfigSpec.ValueSpec valueSpec) {
            this.label = label;
            this.tooltip = tooltip;
            this.value = value;
            this.valueSpec = valueSpec;
        }

        final void setWidget(AbstractWidget widget) {
            this.widget = widget;
            widgets.clear();
            widgets.add(widget);
            if (tooltip != null) widget.setTooltip(Tooltip.create(tooltip));
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float partialTick) {
            int controlWidth = Math.min(190, Math.max(120, width / 3));
            int controlX = left + width - controlWidth - 8;
            int labelX = left + 8;
            int labelY = top + 8;

            widget.setX(controlX);
            widget.setY(top + 2);
            widget.setWidth(controlWidth);
            graphics.drawString(font, label, labelX, labelY, 0xFFFFFF, false);
            widget.render(graphics, mouseX, mouseY, partialTick);

            if (tooltip != null && mouseY >= top && mouseY < top + height
                    && mouseX >= labelX && mouseX < controlX - 6) {
                FastMoveConfigSectionScreen.this.setTooltipForNextRenderPass(tooltip);
            }
        }

        @Override public List<? extends GuiEventListener> children() { return widgets; }
        @Override public List<? extends NarratableEntry> narratables() { return widgets; }

        @SuppressWarnings({"rawtypes", "unchecked"})
        final void setConfigValue(Object newValue) {
            ((ForgeConfigSpec.ConfigValue) value).set(newValue);
        }
    }

    private final class BooleanEntry extends ValueEntry {
        private boolean stagedValue;
        private final Button button;

        BooleanEntry(Component label, Component tooltip, ForgeConfigSpec.ConfigValue<?> value,
                     ForgeConfigSpec.ValueSpec valueSpec, boolean initialValue) {
            super(label, tooltip, value, valueSpec);
            this.stagedValue = initialValue;
            this.button = Button.builder(booleanText(stagedValue), pressed -> {
                stagedValue = !stagedValue;
                pressed.setMessage(booleanText(stagedValue));
            }).bounds(0, 0, 160, 20).build();
            setWidget(button);
        }

        private Component booleanText(boolean value) {
            return Component.translatable(value ? "options.on" : "options.off");
        }

        @Override
        void commit() {
            // Forge 1.20.1 ValueSpec#correct() returns the default for non-range values.
            // Booleans have no range, so passing a valid toggled value through correct()
            // silently replaced it with the default every time Done was pressed.
            setConfigValue(stagedValue);
        }

        @Override
        void resetToDefault() {
            Object defaultValue = valueSpec.getDefault();
            stagedValue = defaultValue instanceof Boolean b && b;
            button.setMessage(booleanText(stagedValue));
        }
    }

    private final class MomentumStrengthSliderEntry extends ValueEntry {
        private int stagedValue;
        private final MomentumSliderButton slider;

        MomentumStrengthSliderEntry(Component label, Component tooltip, ForgeConfigSpec.ConfigValue<?> value,
                                    ForgeConfigSpec.ValueSpec valueSpec, int initialValue) {
            super(label, tooltip, value, valueSpec);
            this.stagedValue = clampPercent(initialValue);
            this.slider = new MomentumSliderButton(stagedValue);
            setWidget(slider);
        }

        private int clampPercent(int value) {
            return Math.max(0, Math.min(200, value));
        }

        @Override
        void commit() {
            Object corrected = valueSpec.correct(stagedValue);
            setConfigValue(corrected);
            if (corrected instanceof Number number) slider.setPercent(number.intValue());
        }

        @Override
        void resetToDefault() {
            Object defaultValue = valueSpec.getDefault();
            slider.setPercent(defaultValue instanceof Number number ? number.intValue() : 100);
        }

        private final class MomentumSliderButton extends AbstractSliderButton {
            MomentumSliderButton(int initialValue) {
                super(0, 0, 160, 20, Component.empty(), clampPercent(initialValue) / 200.0D);
                stagedValue = clampPercent(initialValue);
                updateMessage();
            }

            void setPercent(int percent) {
                stagedValue = clampPercent(percent);
                value = stagedValue / 200.0D;
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.literal(stagedValue + "%"));
            }

            @Override
            protected void applyValue() {
                stagedValue = clampPercent((int) Math.round(value * 200.0D));
                value = stagedValue / 200.0D;
                updateMessage();
            }
        }
    }

    private final class SoundVolumeSliderEntry extends ValueEntry {
        private int stagedValue;
        private final SoundVolumeSliderButton slider;

        SoundVolumeSliderEntry(Component label, Component tooltip, ForgeConfigSpec.ConfigValue<?> value,
                               ForgeConfigSpec.ValueSpec valueSpec, int initialValue) {
            super(label, tooltip, value, valueSpec);
            this.stagedValue = clampPercent(initialValue);
            this.slider = new SoundVolumeSliderButton(stagedValue);
            setWidget(slider);
        }

        private int clampPercent(int value) {
            return Math.max(0, Math.min(100, value));
        }

        @Override
        void commit() {
            Object corrected = valueSpec.correct(stagedValue);
            setConfigValue(corrected);
            if (corrected instanceof Number number) slider.setPercent(number.intValue());
        }

        @Override
        void resetToDefault() {
            Object defaultValue = valueSpec.getDefault();
            slider.setPercent(defaultValue instanceof Number number ? number.intValue() : 80);
        }

        private final class SoundVolumeSliderButton extends AbstractSliderButton {
            SoundVolumeSliderButton(int initialValue) {
                super(0, 0, 160, 20, Component.empty(), clampPercent(initialValue) / 100.0D);
                stagedValue = clampPercent(initialValue);
                updateMessage();
            }

            void setPercent(int percent) {
                stagedValue = clampPercent(percent);
                value = stagedValue / 100.0D;
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.literal(stagedValue + "%"));
            }

            @Override
            protected void applyValue() {
                stagedValue = clampPercent((int) Math.round(value * 100.0D));
                value = stagedValue / 100.0D;
                updateMessage();
            }
        }
    }

    private final class NumberEntry extends ValueEntry {
        private final EditBox editBox;
        private final Class<?> numberType;

        NumberEntry(Component label, Component tooltip, ForgeConfigSpec.ConfigValue<?> value,
                    ForgeConfigSpec.ValueSpec valueSpec, Object initialValue) {
            super(label, tooltip, value, valueSpec);
            this.numberType = initialValue.getClass();
            this.editBox = new EditBox(font, 0, 0, 160, 20, label);
            editBox.setMaxLength(32);
            editBox.setFilter(numberType == Integer.class || numberType == Long.class
                    ? FastMoveConfigSectionScreen::isPartialInteger
                    : FastMoveConfigSectionScreen::isPartialDecimal);
            editBox.setValue(formatNumber(initialValue));
            setWidget(editBox);
        }

        @Override
        void commit() {
            Object parsed = parseNumber(editBox.getValue(), numberType);
            if (parsed == null) {
                try {
                    editBox.setValue(formatNumber(value.get()));
                } catch (Exception ignored) {
                    editBox.setValue(formatNumber(valueSpec.getDefault()));
                }
                return;
            }
            Object corrected = valueSpec.correct(parsed);
            setConfigValue(corrected);
            editBox.setValue(formatNumber(corrected));
        }

        @Override
        void resetToDefault() {
            editBox.setValue(formatNumber(valueSpec.getDefault()));
        }
    }

    private static boolean isPartialInteger(String value) {
        return value.isEmpty() || value.equals("-") || value.matches("-?\\d+");
    }

    private static boolean isPartialDecimal(String value) {
        return value.isEmpty() || value.equals("-") || value.equals(".") || value.equals("-.")
                || value.matches("-?(?:\\d+\\.?\\d*|\\.\\d*)");
    }

    private static Object parseNumber(String value, Class<?> type) {
        try {
            if (type == Integer.class) return Integer.parseInt(value);
            if (type == Long.class) return Long.parseLong(value);
            if (type == Float.class) return Float.parseFloat(value);
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String formatNumber(Object value) {
        return String.valueOf(value);
    }
}
