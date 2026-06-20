package gnu.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import gnu.client.common.GnuLog;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ConfigManager {

    public static final ConfigManager INSTANCE = new ConfigManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long SAVE_DEBOUNCE_MS = 500L;

    private final Path configPath;
    private boolean loading;
    private long lastSaveAtMs;

    private ConfigManager() {
        String home = System.getProperty("user.home");
        if (home != null)
            configPath = Paths.get(home, ".config", "gnuclient", "config.json");
        else
            configPath = Paths.get("/tmp/gnuclient_config.json");
    }

    public static ConfigManager instance() {
        return INSTANCE;
    }

    public Path getConfigPath() {
        return configPath;
    }

    public boolean isLoading() {
        return loading;
    }

    public static void setLoading(boolean loading) {
        INSTANCE.loading = loading;
    }

    public void load() {
        if (!Files.isRegularFile(configPath)) {
            GnuLog.log("config: no file at " + configPath);
            return;
        }

        loading = true;
        try (Reader reader = Files.newBufferedReader(configPath)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null || !root.has("modules"))
                return;

            JsonObject modulesJson = root.getAsJsonObject("modules");
            for (Module module : ModuleManager.INSTANCE.all()) {
                if (modulesJson.has(module.getName()))
                    module.deserialize(modulesJson.getAsJsonObject(module.getName()));
            }
            GnuLog.log("config: loaded from " + configPath);
        } catch (Exception e) {
            GnuLog.log("config: load failed " + e);
        } finally {
            loading = false;
        }
    }

    public void save() {
        if (loading)
            return;
        long now = System.currentTimeMillis();
        if (now - lastSaveAtMs < SAVE_DEBOUNCE_MS)
            return;
        lastSaveAtMs = now;

        JsonObject root = new JsonObject();
        JsonObject modulesJson = new JsonObject();
        for (Module module : ModuleManager.INSTANCE.all()) {
            modulesJson.add(module.getName(), module.serialize());
        }
        root.add("modules", modulesJson);

        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                GSON.toJson(root, writer);
            }
            GnuLog.log("config: saved to " + configPath);
        } catch (IOException e) {
            GnuLog.log("config: save failed " + e);
        }
    }
}
