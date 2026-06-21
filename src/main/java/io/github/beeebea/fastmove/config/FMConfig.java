package io.github.beeebea.fastmove.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class FMConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private boolean enableFastMove = true;

    private boolean diveRollEnabled = true;
    private int diveRollStaminaCost = 20;
    private double diveRollSpeedBoostMultiplier = 1.0;
    private int diveRollCoolDown = 0;
    private boolean diveRollWhenSwimming = false;
    private boolean diveRollWhenFlying = false;

    private boolean wallRunEnabled = true;
    private int wallRunStaminaCost = 0;
    private double wallRunSpeedBoostMultiplier = 1.0;
    private int wallRunDurationTicks = 60;

    private boolean slideEnabled = true;
    private int slideStaminaCost = 10;
    private double slideSpeedBoostMultiplier = 1.0;
    private int slideCoolDown = 0;

    // Kept for compatibility with the original config shape. The Fabric-only API hooks are disabled in this NeoForge port.
    private boolean useCombatRoll = false;
    private boolean useParaglider = false;

    public static FMConfig createAndLoad() {
        FMConfig config = new FMConfig();
        Path path = FMLPaths.CONFIGDIR.get().resolve("fastmove.json");
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    config.read(json);
                }
            }
            config.save(path);
        } catch (Exception ignored) {
            // If a config is malformed, keep safe defaults and overwrite it next save.
            try {
                config.save(path);
            } catch (IOException ignoredAgain) {
            }
        }
        return config;
    }

    private void read(JsonObject json) {
        enableFastMove = getBool(json, "enableFastMove", enableFastMove);
        diveRollEnabled = getBool(json, "diveRollEnabled", diveRollEnabled);
        diveRollStaminaCost = getInt(json, "diveRollStaminaCost", diveRollStaminaCost);
        diveRollSpeedBoostMultiplier = getDouble(json, "diveRollSpeedBoostMultiplier", diveRollSpeedBoostMultiplier);
        diveRollCoolDown = getInt(json, "diveRollCoolDown", diveRollCoolDown);
        diveRollWhenSwimming = getBool(json, "diveRollWhenSwimming", diveRollWhenSwimming);
        diveRollWhenFlying = getBool(json, "diveRollWhenFlying", diveRollWhenFlying);
        wallRunEnabled = getBool(json, "wallRunEnabled", wallRunEnabled);
        wallRunStaminaCost = getInt(json, "wallRunStaminaCost", wallRunStaminaCost);
        wallRunSpeedBoostMultiplier = getDouble(json, "wallRunSpeedBoostMultiplier", wallRunSpeedBoostMultiplier);
        wallRunDurationTicks = getInt(json, "wallRunDurationTicks", wallRunDurationTicks);
        slideEnabled = getBool(json, "slideEnabled", slideEnabled);
        slideStaminaCost = getInt(json, "slideStaminaCost", slideStaminaCost);
        slideSpeedBoostMultiplier = getDouble(json, "slideSpeedBoostMultiplier", slideSpeedBoostMultiplier);
        slideCoolDown = getInt(json, "slideCoolDown", slideCoolDown);
        useCombatRoll = getBool(json, "useCombatRoll", useCombatRoll);
        useParaglider = getBool(json, "useParaglider", useParaglider);
    }

    private void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(this, writer);
        }
    }

    private static boolean getBool(JsonObject json, String key, boolean fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsBoolean() : fallback;
    }

    private static int getInt(JsonObject json, String key, int fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsInt() : fallback;
    }

    private static double getDouble(JsonObject json, String key, double fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsDouble() : fallback;
    }

    public boolean enableFastMove() { return enableFastMove; }
    public boolean diveRollEnabled() { return diveRollEnabled; }
    public int diveRollStaminaCost() { return diveRollStaminaCost; }
    public double diveRollSpeedBoostMultiplier() { return diveRollSpeedBoostMultiplier; }
    public int diveRollCoolDown() { return diveRollCoolDown; }
    public boolean diveRollWhenSwimming() { return diveRollWhenSwimming; }
    public boolean diveRollWhenFlying() { return diveRollWhenFlying; }
    public boolean wallRunEnabled() { return wallRunEnabled; }
    public int wallRunStaminaCost() { return wallRunStaminaCost; }
    public double wallRunSpeedBoostMultiplier() { return wallRunSpeedBoostMultiplier; }
    public int wallRunDurationTicks() { return Math.max(1, wallRunDurationTicks); }
    public boolean slideEnabled() { return slideEnabled; }
    public int slideStaminaCost() { return slideStaminaCost; }
    public double slideSpeedBoostMultiplier() { return slideSpeedBoostMultiplier; }
    public int slideCoolDown() { return slideCoolDown; }
    public boolean useCombatRoll() { return useCombatRoll; }
    public boolean useParaglider() { return useParaglider; }
}
