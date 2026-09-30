package dev.tukisg.anticooldownhud;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

final class SignatureCatalog {
    static List<SignatureCheck.Profile> load(File file) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
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
                            entry.getBoolean("require-translations", true)));
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
