package dev.tukisg.anticooldownhud;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
