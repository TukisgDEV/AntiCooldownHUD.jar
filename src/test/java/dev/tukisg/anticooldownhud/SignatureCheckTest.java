package dev.tukisg.anticooldownhud;

import static dev.tukisg.anticooldownhud.SignatureCheck.Result.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;

class SignatureCheckTest {
    static final SignatureCheck.Profile PROFILE =
            new SignatureCheck.Profile(
                    "combat",
                    new SignatureCheck.Translation(
                            "activity.module.auto_mace.name",
                            Set.of("AutoMace", "Авто-булава (AutoMace)")),
                    new SignatureCheck.Translation(
                            "activity.module.auto_totem.name",
                            Set.of("AutoTotem", "Авто-тотем (AutoTotem)")),
                    "key.cooldown_hud.open");
    static final SignatureCheck.Challenge CHALLENGE =
            new SignatureCheck.Challenge(PROFILE, "challenge123");

    static String[] match(SignatureCheck.Challenge challenge) {
        return new String[] {"AutoMace", "AutoTotem", "H", challenge.nonce()};
    }

    static String[] vanilla(SignatureCheck.Challenge challenge) {
        return new String[] {
            challenge.fallbackA(),
            challenge.fallbackB(),
            challenge.profile().keybind(),
            challenge.nonce()
        };
    }

    static List<SignatureCheck.Profile> catalog() throws Exception {
        return SignatureCatalog.load(
                new File(SignatureCheckTest.class.getResource("/signatures.yml").toURI()));
    }

    @Test
    void vanillaAndTranslationsAloneDoNotDetect() {
        assertEquals(NO_MATCH, CHALLENGE.evaluate(vanilla(CHALLENGE)));
        assertEquals(
                PARTIAL,
                CHALLENGE.evaluate(
                        new String[] {
                            "AutoMace", "AutoTotem", PROFILE.keybind(), CHALLENGE.nonce()
                        }));
        assertEquals(
                PARTIAL,
                CHALLENGE.evaluate(new String[] {"AutoMace", "fallback", "H", CHALLENGE.nonce()}));
        assertEquals(
                PARTIAL,
                CHALLENGE.evaluate(
                        new String[] {
                            CHALLENGE.fallbackA(), CHALLENGE.fallbackB(), "H", CHALLENGE.nonce()
                        }));
    }

    @ParameterizedTest
    @ValueSource(strings = {"H", "Right Shift", "Не назначено", "Mouse Button 4"})
    void supportsReassignedAndLocalizedKeys(String key) {
        assertEquals(
                DETECTED,
                CHALLENGE.evaluate(new String[] {"AutoMace", "AutoTotem", key, CHALLENGE.nonce()}));
    }

    @Test
    void invalidRepliesNeverDetect() {
        assertEquals(INVALID, CHALLENGE.evaluate(null));
        assertEquals(INVALID, CHALLENGE.evaluate(new String[] {"AutoMace"}));
        assertEquals(
                INVALID,
                CHALLENGE.evaluate(
                        new String[] {"AutoMace", "AutoTotem", null, CHALLENGE.nonce()}));
        assertEquals(
                INVALID,
                CHALLENGE.evaluate(new String[] {"AutoMace", "AutoTotem", "H", "old-nonce"}));
        assertEquals(
                INVALID,
                CHALLENGE.evaluate(
                        new String[] {"AutoMace", "AutoTotem", "H", CHALLENGE.nonce(), "extra"}));
        assertEquals(
                INVALID,
                CHALLENGE.evaluate(
                        new String[] {"AutoMace", "AutoTotem", "H\n", CHALLENGE.nonce()}));
        assertEquals(
                INVALID,
                CHALLENGE.evaluate(
                        new String[] {"x".repeat(385), "AutoTotem", "H", CHALLENGE.nonce()}));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                "cg_a_challenge123",
                "cg_b_challenge123",
                "challenge123",
                "key.cooldown_hud.open"
            })
    void placeholdersNeverCountAsKeybinds(String key) {
        assertEquals(
                PARTIAL,
                CHALLENGE.evaluate(new String[] {"AutoMace", "AutoTotem", key, CHALLENGE.nonce()}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy-en_us", "legacy-ru_ru", "current-en_us", "current-ru_ru"})
    void shippedSignaturesMatchTranslationsExtractedFromBothJars(String fixture) throws Exception {
        Properties translations = new Properties();
        try (var reader =
                new InputStreamReader(
                        getClass().getResourceAsStream("/fixtures/" + fixture + ".properties"),
                        StandardCharsets.UTF_8)) {
            translations.load(reader);
        }
        int detected = 0;
        for (SignatureCheck.Profile profile : catalog()) {
            if (!profile.id().startsWith("cooldownhud-")) continue;
            var challenge = new SignatureCheck.Challenge(profile, "fixture12345");
            String[] lines = {
                translations.getProperty(profile.first().key(), challenge.fallbackA()),
                translations.getProperty(profile.second().key(), challenge.fallbackB()),
                profile.keybind().equals(translations.getProperty("keybind.registered"))
                        ? "H"
                        : profile.keybind(),
                challenge.nonce()
            };
            if (challenge.evaluate(lines) == DETECTED) detected++;
            else assertEquals("cooldownhud-legacy", profile.id());
            assertEquals(NO_MATCH, challenge.evaluate(vanilla(challenge)));
        }
        assertEquals(fixture.startsWith("legacy") ? 6 : 5, detected);
    }

    @Test
    void verifiedUniqueKeybindFallbackWorksWithoutTranslations() throws Exception {
        var profile =
                catalog().stream()
                        .filter(p -> p.id().equals("cooldownhud-keybind"))
                        .findFirst()
                        .orElseThrow();
        var challenge = new SignatureCheck.Challenge(profile, "legacyKey123");
        var lines = vanilla(challenge);
        lines[2] = "H";
        assertEquals(DETECTED, challenge.evaluate(lines));
        assertEquals(NO_MATCH, challenge.evaluate(vanilla(challenge)));
        lines[3] = "staleNonce";
        assertEquals(INVALID, challenge.evaluate(lines));
    }
}
