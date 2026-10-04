package dev.tukisg.anticooldownhud;

import static dev.tukisg.anticooldownhud.SignatureCheckTest.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

class FreecamDetectionTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "freecam-fabric-1.3.6+mc1.21.11-en_us",
                "freecam-fabric-1.3.6+mc1.21.11-ru_ru",
                "freecam-fabric-1.4.1+mc1.21.11-en_us",
                "freecam-fabric-1.4.1+mc1.21.11-ru_ru",
                "freecam-neoforge-1.4.1+mc1.21.11-en_us",
                "freecam-neoforge-1.4.1+mc1.21.11-ru_ru",
                "freecam-fabric-1.5.0-alpha.2+mc1.21.11-en_us",
                "freecam-fabric-1.5.0-alpha.2+mc1.21.11-ru_ru"
            })
    void detectsActualFreecamSignaturesAfterSkippingCooldownHud(String fixture) throws Exception {
        var translations = new Properties();
        try (var reader =
                new InputStreamReader(
                        getClass()
                                .getResourceAsStream(
                                        "/fixtures/freecam/" + fixture + ".properties"),
                        StandardCharsets.UTF_8)) {
            translations.load(reader);
        }
        var profiles = catalog();
        var session = new DetectionSession(profiles, 1);
        int confirmations = 0;
        for (int i = 0; i < profiles.size() + 2; i++) {
            var profile = session.profile();
            var challenge = new SignatureCheck.Challenge(profile, "freecamNonce" + i);
            if (profile.probeFormat() == SignatureCheck.ProbeFormat.THREE_TRANSLATIONS) {
                assertEquals(
                        DetectionSession.Step.NEXT, session.reply(challenge, vanilla(challenge)));
                continue;
            }
            String[] lines = {
                translations.getProperty(profile.first().key(), challenge.fallbackA()),
                translations.getProperty(profile.second().key(), challenge.fallbackB()),
                profile.keybind().equals(translations.getProperty("keybind.registered"))
                        ? "F4"
                        : profile.keybind(),
                challenge.nonce()
            };
            var step = session.reply(challenge, lines);
            if (step == DetectionSession.Step.CONFIRM) confirmations++;
            else if (step == DetectionSession.Step.DETECTED) {
                assertEquals(1, confirmations);
                assertEquals(translations.getProperty("signature.id"), profile.id());
                assertEquals("Freecam", profile.modName());
                assertEquals(SignatureCheck.Action.KICK, profile.action());
                return;
            } else assertEquals(DetectionSession.Step.NEXT, step);
        }
        fail("Freecam signature was not detected");
    }

    @ParameterizedTest
    @ValueSource(strings = {"freecam-legacy", "freecam-modern"})
    void freecamRequiresTranslationsAndFreshConfirmation(String id) throws Exception {
        var profile = catalog().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
        var session = new DetectionSession(java.util.List.of(profile), 1);
        var first = new SignatureCheck.Challenge(profile, "freecamTest123");
        var keyOnly = vanilla(first);
        keyOnly[2] = "F4";
        assertEquals(SignatureCheck.Result.PARTIAL, first.evaluate(keyOnly));
        assertEquals(
                DetectionSession.Step.CONFIRM,
                session.reply(
                        first,
                        new String[] {"Control Player", "Reset Tripod", "F4", first.nonce()}));
        var second = new SignatureCheck.Challenge(profile, "freecamTest456");
        assertEquals(DetectionSession.Step.INCONCLUSIVE, session.reply(second, vanilla(second)));
    }
}
