package dev.tukisg.anticooldownhud;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

final class SignatureCatalog {
    static List<SignatureCheck.Profile> load(File file) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        return parse(config);
    }

    static List<SignatureCheck.Profile> load(File file, Reader bundled) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.load(bundled);
        ConfigurationSection additions = defaults.getConfigurationSection("signatures");
        if (additions == null || config.getConfigurationSection("signatures") == null)
            throw new IllegalArgumentException("Missing signatures section");
        boolean changed = false;
        for (String id : additions.getKeys(false)) {
            String base = "signatures." + id;
            if (config.contains(base)) continue;
            ConfigurationSection entry = additions.getConfigurationSection(id);
            for (String key : entry.getKeys(true)) {
                if (!entry.isConfigurationSection(key))
                    config.set(base + "." + key, entry.get(key));
            }
            changed = true;
        }
        List<SignatureCheck.Profile> result = parse(config);
        if (changed) config.save(file);
        return result;
    }

    private static List<SignatureCheck.Profile> parse(YamlConfiguration config) {
        ConfigurationSection entries = config.getConfigurationSection("signatures");
        if (entries == null) throw new IllegalArgumentException("Missing signatures section");
        List<SignatureCheck.Profile> profiles = new ArrayList<>();
        for (String id : entries.getKeys(false)) {
            ConfigurationSection entry = entries.getConfigurationSection(id);
            if (entry == null) throw new IllegalArgumentException("Invalid signature: " + id);
            if (!entry.getBoolean("enabled", true)) continue;
            profiles.add(
                    new SignatureCheck.Profile(
                            id,
                            translation(entry, "first"),
                            translation(entry, "second"),
                            entry.getString("keybind"),
                            entry.getBoolean("require-translations", true),
                            entry.getString(
                                    "mod-name",
                                    id.startsWith("cooldownhud-")
                                            ? "CooldownHUD"
                                            : id.startsWith("freecam-") ? "Freecam" : id),
                            SignatureCheck.Action.valueOf(
                                    entry.getString(
                                                    "action",
                                                    id.startsWith("freecam-") ? "KICK" : "BAN")
                                            .toUpperCase(Locale.ROOT)),
                            SignatureCheck.ProbeFormat.valueOf(
                                    entry.getString("probe-format", "DIRECT")
                                            .toUpperCase(Locale.ROOT)),
                            entry.isConfigurationSection("third")
                                    ? translation(entry, "third")
                                    : null));
        }
        if (profiles.isEmpty() || profiles.size() > 64)
            throw new IllegalArgumentException("Enable between 1 and 64 signatures");
        return List.copyOf(profiles);
    }

    private static SignatureCheck.Translation translation(ConfigurationSection entry, String name) {
        return new SignatureCheck.Translation(
                entry.getString(name + ".key"),
                new HashSet<>(entry.getStringList(name + ".values")));
    }

    private SignatureCatalog() {}
}
