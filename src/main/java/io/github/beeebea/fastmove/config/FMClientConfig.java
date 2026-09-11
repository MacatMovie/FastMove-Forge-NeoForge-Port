package io.github.beeebea.fastmove.config;

import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.common.ForgeConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Client-only audiovisual preferences. These values are never synced by servers. */
public final class FMClientConfig {
    public static final String FILE_NAME = "fastmove-client.toml";
    private static final Logger LOGGER = LoggerFactory.getLogger("fastmove/client_config");

    public static final FMClientConfig INSTANCE;
    public static final ForgeConfigSpec SPEC;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        INSTANCE = new FMClientConfig(builder);
        SPEC = builder.build();
    }

    private final ForgeConfigSpec.BooleanValue wallRunCameraTiltEnabledValue;
    private final ForgeConfigSpec.DoubleValue wallRunCameraTiltDegreesValue;
    private final ForgeConfigSpec.DoubleValue wallRunCameraTiltSmoothingValue;

    private final ForgeConfigSpec.BooleanValue slideCameraTiltEnabledValue;
    private final ForgeConfigSpec.DoubleValue slideCameraTiltDegreesValue;
    private final ForgeConfigSpec.DoubleValue slideCameraTiltSmoothingValue;

    private final ForgeConfigSpec.IntValue slideSoundVolumePercentValue;

    private final ForgeConfigSpec.BooleanValue diveRollCameraRollEnabledValue;

    private static Boolean pendingTiltEnabled;
    private static Double pendingTiltDegrees;

    private FMClientConfig(ForgeConfigSpec.Builder builder) {
        wallRunCameraTiltEnabledValue = builder
                .comment(
                        "Tilt the first-person camera sideways while wall-running.",
                        "This client-only accessibility preference can never be forced by a server."
                )
                .translation("text.config.fastmove.client.wallRunCameraTiltEnabled")
                .define("cameraWallRunTiltEnabled", true);
        wallRunCameraTiltDegreesValue = builder
                .comment(
                        "Maximum sideways camera tilt while wall-running, measured in degrees.",
                        "Set to 0 to remove the tilt without disabling the option."
                )
                .translation("text.config.fastmove.client.wallRunCameraTiltDegrees")
                .defineInRange("cameraWallRunTiltDegrees", 10.0D, 0.0D, 30.0D);
        wallRunCameraTiltSmoothingValue = builder
                .comment(
                        "How quickly the camera eases into and out of the wall-run tilt.",
                        "Higher values react faster; 1.0 changes instantly."
                )
                .translation("text.config.fastmove.client.wallRunCameraTiltSmoothing")
                .defineInRange("cameraWallRunTiltSmoothing", 0.25D, 0.01D, 1.0D);

        slideCameraTiltEnabledValue = builder
                .comment(
                        "Angle the first-person camera slightly upward while sliding so the low camera feels like a body slide rather than simply becoming shorter.",
                        "Disable this if you prefer a completely stable camera."
                )
                .translation("text.config.fastmove.client.slideCameraTiltEnabled")
                .define("cameraSlideTiltEnabled", true);
        slideCameraTiltDegreesValue = builder
                .comment("Upward camera angle while sliding, measured in degrees.")
                .translation("text.config.fastmove.client.slideCameraTiltDegrees")
                .defineInRange("cameraSlideTiltDegrees", 8.0D, 0.0D, 25.0D);
        slideCameraTiltSmoothingValue = builder
                .comment(
                        "How quickly the camera eases into and out of the slide angle.",
                        "Higher values react faster; 1.0 changes instantly."
                )
                .translation("text.config.fastmove.client.slideCameraTiltSmoothing")
                .defineInRange("cameraSlideTiltSmoothing", 0.45D, 0.01D, 1.0D);

        slideSoundVolumePercentValue = builder
                .comment(
                        "Volume of FastMove's sliding sound, as a percentage.",
                        "100% matches the previous sound level; 0% mutes the sliding sound."
                )
                .translation("text.config.fastmove.client.slideSoundVolumePercent")
                .defineInRange("slideSoundVolumePercent", 80, 0, 100);

        diveRollCameraRollEnabledValue = builder
                .comment(
                        "Rotate the first-person camera through a full forward roll while the player dive-rolls.",
                        "This is intentionally a strong visual effect and may cause motion sickness, so it is disabled by default."
                )
                .translation("text.config.fastmove.client.diveRollCameraRollEnabled")
                .define("cameraDiveRollFullRollEnabled", false);
    }

    public static void preparePass1AMigration() {
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        if (!Files.isRegularFile(path)) return;

        try {
            String toml = Files.readString(path);
            if (!toml.contains("[camera]")) return;

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
                if (!section.equals("camera")) continue;
                int equals = line.indexOf('=');
                if (equals < 0) continue;
                String key = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();
                if (key.equals("wallRunCameraTiltEnabled")) pendingTiltEnabled = Boolean.parseBoolean(value);
                if (key.equals("wallRunCameraTiltDegrees")) pendingTiltDegrees = Double.parseDouble(value);
            }

            Path archived = nextArchivePath(path, ".pass1a.migrated");
            Files.move(path, archived, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Found the earlier FastMove client test config; it will be flattened into the new layout.");
        } catch (Exception exception) {
            pendingTiltEnabled = null;
            pendingTiltDegrees = null;
            LOGGER.warn("Could not migrate the earlier FastMove client config; defaults will be used.", exception);
        }
    }

    public static void onConfigLoading(ModConfigEvent.Loading event) {
        ModConfig config = event.getConfig();
        if (config.getSpec() != SPEC || (pendingTiltEnabled == null && pendingTiltDegrees == null)) return;

        try {
            if (pendingTiltEnabled != null) INSTANCE.wallRunCameraTiltEnabledValue.set(pendingTiltEnabled);
            if (pendingTiltDegrees != null) INSTANCE.wallRunCameraTiltDegreesValue.set(
                    Math.max(0.0D, Math.min(30.0D, pendingTiltDegrees))
            );
            SPEC.save();
        } finally {
            pendingTiltEnabled = null;
            pendingTiltDegrees = null;
        }
    }

    private static Path nextArchivePath(Path source, String suffix) {
        Path archived = source.resolveSibling(source.getFileName() + suffix);
        if (!Files.exists(archived)) return archived;
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return source.resolveSibling(source.getFileName() + suffix + "-" + timestamp);
    }

    public boolean wallRunCameraTiltEnabled() { return wallRunCameraTiltEnabledValue.get(); }
    public double wallRunCameraTiltDegrees() { return wallRunCameraTiltDegreesValue.get(); }
    public double wallRunCameraTiltSmoothing() { return wallRunCameraTiltSmoothingValue.get(); }

    public boolean slideCameraTiltEnabled() { return slideCameraTiltEnabledValue.get(); }
    public double slideCameraTiltDegrees() { return slideCameraTiltDegreesValue.get(); }
    public double slideCameraTiltSmoothing() { return slideCameraTiltSmoothingValue.get(); }

    public int slideSoundVolumePercent() {
        return SPEC.isLoaded() ? slideSoundVolumePercentValue.get() : slideSoundVolumePercentValue.getDefault();
    }

    public boolean diveRollCameraRollEnabled() { return diveRollCameraRollEnabledValue.get(); }
}
