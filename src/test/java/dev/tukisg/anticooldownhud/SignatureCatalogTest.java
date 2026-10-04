package dev.tukisg.anticooldownhud;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class SignatureCatalogTest {
    @TempDir Path directory;

    @Test
    void rejectsEmptyAndUnsafeSignatures() throws Exception {
        Path file = directory.resolve("signatures.yml");
        Files.writeString(file, "signatures: {}\n");
        assertThrows(IllegalArgumentException.class, () -> SignatureCatalog.load(file.toFile()));
        Files.writeString(
                file,
                "signatures:\n"
                        + "  unsafe:\n"
                        + "    keybind: key.hud\n"
                        + "    first:\n"
                        + "      key: same.key\n"
                        + "      values: ['One']\n"
                        + "    second:\n"
                        + "      key: same.key\n"
                        + "      values: ['Two']\n");
        assertThrows(IllegalArgumentException.class, () -> SignatureCatalog.load(file.toFile()));
    }

    @Test
    void loadsCustomProfilesWithoutRebuild() throws Exception {
        Path file = directory.resolve("signatures.yml");
        Files.writeString(
                file,
                "signatures:\n"
                        + "  custom:\n"
                        + "    keybind: custom.key\n"
                        + "    first:\n"
                        + "      key: custom.one\n"
                        + "      values: ['One']\n"
                        + "    second:\n"
                        + "      key: custom.two\n"
                        + "      values: ['Two']\n"
                        + "  disabled:\n"
                        + "    enabled: false\n");
        var profiles = SignatureCatalog.load(file.toFile());
        assertEquals(1, profiles.size());
        assertEquals("custom", profiles.getFirst().id());
        assertThrows(UnsupportedOperationException.class, () -> profiles.clear());
    }

    private java.util.List<SignatureCheck.Profile> migrate(Path file) throws Exception {
        try (var reader =
                new InputStreamReader(
                        getClass().getResourceAsStream("/signatures.yml"),
                        StandardCharsets.UTF_8)) {
            return SignatureCatalog.load(file.toFile(), reader);
        }
    }

    @Test
    void oldConfigurationGainsFreecamWithoutOverwritingExistingSettings() throws Exception {
        Path file = directory.resolve("signatures.yml");
        YamlConfiguration config;
        try (var reader =
                new InputStreamReader(
                        getClass().getResourceAsStream("/signatures.yml"),
                        StandardCharsets.UTF_8)) {
            config = YamlConfiguration.loadConfiguration(reader);
        }
        config.set("signatures.freecam-legacy", null);
        config.set("signatures.freecam-modern", null);
        config.set("signatures.cooldownhud-combat.enabled", false);
        config.set("signatures.cooldownhud-legacy.first.values", java.util.List.of("Custom text"));
        config.save(file.toFile());
        var result = migrate(file);
        assertEquals(
                2,
                result.stream()
                        .filter(
                                p ->
                                        p.modName().equals("Freecam")
                                                && p.action() == SignatureCheck.Action.KICK)
                        .count());
        assertTrue(result.stream().noneMatch(p -> p.id().equals("cooldownhud-combat")));
        var saved = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(
                java.util.List.of("Custom text"),
                saved.getStringList("signatures.cooldownhud-legacy.first.values"));
        String once = Files.readString(file);
        migrate(file);
        assertEquals(once, Files.readString(file));
        saved.set("signatures.freecam-legacy.enabled", false);
        saved.save(file.toFile());
        assertTrue(migrate(file).stream().noneMatch(p -> p.id().equals("freecam-legacy")));
    }

    @Test
    void existingConfigurationGainsNestedProfileAndPreservesAnExplicitDisable() throws Exception {
        Path file = directory.resolve("nested-signatures.yml");
        YamlConfiguration config;
        try (var reader =
                new InputStreamReader(
                        getClass().getResourceAsStream("/signatures.yml"),
                        StandardCharsets.UTF_8)) {
            config = YamlConfiguration.loadConfiguration(reader);
        }
        config.set("signatures.cooldownhud-memoryleakfix", null);
        config.save(file.toFile());
        var added =
                migrate(file).stream()
                        .filter(p -> p.id().equals("cooldownhud-memoryleakfix"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(SignatureCheck.ProbeFormat.TRANSLATION_ARGUMENT, added.probeFormat());
        assertEquals(SignatureCheck.Action.BAN, added.action());
        config = YamlConfiguration.loadConfiguration(file.toFile());
        config.set("signatures.cooldownhud-memoryleakfix.enabled", false);
        config.save(file.toFile());
        assertTrue(migrate(file).stream().noneMatch(p -> p.id().equals(added.id())));
    }

    @Test
    void installsThreeTranslationProfileOnceAndPreservesExistingOverrides() throws Exception {
        Path file = directory.resolve("localization.yml");
        YamlConfiguration config;
        try (var reader =
                new InputStreamReader(
                        getClass().getResourceAsStream("/signatures.yml"),
                        StandardCharsets.UTF_8)) {
            config = YamlConfiguration.loadConfiguration(reader);
        }
        config.set("signatures.cooldownhud-memoryleakfix-localization", null);
        config.set("signatures.cooldownhud-memoryleakfix.enabled", false);
        config.save(file.toFile());
        var profiles = migrate(file);
        var added =
                profiles.stream()
                        .filter(p -> p.id().equals("cooldownhud-memoryleakfix-localization"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(SignatureCheck.ProbeFormat.THREE_TRANSLATIONS, added.probeFormat());
        assertTrue(added.requireTranslations());
        assertEquals("activity.module.auto_anchor.name", added.third().key());
        assertEquals(SignatureCheck.Action.BAN, added.action());
        assertTrue(profiles.stream().noneMatch(p -> p.id().equals("cooldownhud-memoryleakfix")));
        String once = Files.readString(file);
        migrate(file);
        assertEquals(once, Files.readString(file));
    }

    @Test
    void invalidActionDoesNotSavePartialMigration() throws Exception {
        Path file = directory.resolve("signatures.yml");
        String original =
                "signatures:\n  custom:\n    action: INVALID\n"
                        + "    keybind: key.custom\n    first:\n      key: custom.first\n"
                        + "      values: ['One']\n    second:\n      key: custom.second\n"
                        + "      values: ['Two']\n";
        Files.writeString(file, original);
        assertThrows(IllegalArgumentException.class, () -> migrate(file));
        assertEquals(original, Files.readString(file));
    }
}
