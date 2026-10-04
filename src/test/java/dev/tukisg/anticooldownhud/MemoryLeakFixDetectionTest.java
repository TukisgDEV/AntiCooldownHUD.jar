package dev.tukisg.anticooldownhud;

import static dev.tukisg.anticooldownhud.SignatureCheckTest.catalog;

import static org.junit.jupiter.api.Assertions.*;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

class MemoryLeakFixDetectionTest {
    private static SignatureCheck.Profile profile() throws Exception {
        return catalog().stream()
                .filter(p -> p.id().equals("cooldownhud-memoryleakfix"))
                .findFirst()
                .orElseThrow();
    }

    private static Properties translations(String language) throws Exception {
        var properties = new Properties();
        try (var reader =
                new InputStreamReader(
                        MemoryLeakFixDetectionTest.class.getResourceAsStream(
                                "/fixtures/memoryleakfix-" + language + ".properties"),
                        StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static String render(Component component, Properties translations, boolean filtered) {
        assertTrue(component.children().isEmpty());
        if (component instanceof TextComponent text) return text.content();
        if (component instanceof KeybindComponent key) {
            return filtered
                    ? key.keybind()
                    : translations.getProperty(key.keybind(), key.keybind());
        }
        var text = (TranslatableComponent) component;
        if (text.key().equals("options.value")) {
            assertTrue(text.fallback().startsWith("cg_wrap_"));
            assertEquals(1, text.arguments().size());
            String format = translations.getProperty("options.value", "%s");
            if (!format.equals("%s")) return format;
            return render((Component) text.arguments().getFirst().value(), translations, false);
        }
        String fallback = text.fallback() == null ? text.key() : text.fallback();
        return filtered ? fallback : translations.getProperty(text.key(), fallback);
    }

    private static String[] reply(
            SignatureCheck.Challenge challenge, Properties translations, boolean filtered) {
        return ProbeText.lines(challenge).stream()
                .map(c -> render(c, translations, filtered))
                .toArray(String[]::new);
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "ru_ru"})
    void nestedComponentsResolveWhereBundledClientSpooferFiltersDirectComponents(String language)
            throws Exception {
        var data = translations(language);
        var direct = new SignatureCheck.Challenge(catalog().getFirst(), "directNonce123");
        assertEquals(SignatureCheck.Result.NO_MATCH, direct.evaluate(reply(direct, data, true)));
        var nested = new SignatureCheck.Challenge(profile(), "nestedNonce123");
        assertEquals(SignatureCheck.Result.DETECTED, nested.evaluate(reply(nested, data, true)));
        assertEquals(SignatureCheck.Result.DETECTED, nested.evaluate(reply(nested, data, false)));
        assertEquals(SignatureCheck.Action.BAN, nested.profile().action());
        assertEquals("У вас был найден запрещенный мод: CooldownHUD!", nested.profile().reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "ru_ru"})
    void missingDirectKeyDoesNotSkipNestedCheckAndBanNeedsTwoFreshMatches(String language)
            throws Exception {
        var session = new DetectionSession(catalog(), 1);
        var data = translations(language);
        int confirmations = 0;
        for (int i = 0; i < 8; i++) {
            var challenge = new SignatureCheck.Challenge(session.profile(), "sessionNonce" + i);
            var step = session.reply(challenge, reply(challenge, data, true));
            if (step == DetectionSession.Step.CONFIRM) confirmations++;
            else if (step == DetectionSession.Step.DETECTED) {
                assertEquals(1, confirmations);
                assertEquals("cooldownhud-memoryleakfix", session.profile().id());
                return;
            } else assertEquals(DetectionSession.Step.NEXT, step);
        }
        fail("The nested signature was skipped");
    }

    @Test
    void vanillaAndClientSpooferAloneAreNotDetected() throws Exception {
        var data = new Properties();
        data.setProperty("memoryleakfix.name", "MemoryLeakFix");
        data.setProperty("clientspoofer.name", "Client Spoofer");
        data.setProperty("key.freecam.toggle", "Toggle Freecam");
        var challenge = new SignatureCheck.Challenge(profile(), "cleanNonce123");
        assertEquals(
                SignatureCheck.Result.NO_MATCH, challenge.evaluate(reply(challenge, data, true)));
        assertEquals(
                SignatureCheck.Result.NO_MATCH, challenge.evaluate(reply(challenge, data, false)));
    }

    @Test
    void partialSignatureAndChangedVanillaWrapperNeverDetect() throws Exception {
        var data = translations("en_us");
        var challenge = new SignatureCheck.Challenge(profile(), "partialNonce123");
        data.remove(challenge.profile().second().key());
        assertEquals(
                SignatureCheck.Result.PARTIAL, challenge.evaluate(reply(challenge, data, true)));
        data = translations("en_us");
        data.setProperty("options.value", "Modified option");
        assertEquals(
                SignatureCheck.Result.PARTIAL, challenge.evaluate(reply(challenge, data, true)));
    }

    @Test
    void staleNonceCannotConfirmNestedDetection() throws Exception {
        var session = new DetectionSession(List.of(profile()), 1);
        var first = new SignatureCheck.Challenge(profile(), "firstNonce123");
        var second = new SignatureCheck.Challenge(profile(), "secondNonce123");
        var lines = reply(first, translations("en_us"), true);
        assertEquals(DetectionSession.Step.CONFIRM, session.reply(first, lines));
        assertEquals(DetectionSession.Step.INCONCLUSIVE, session.reply(second, lines));
    }

    @Test
    void directProbeContentsAreUnchanged() throws Exception {
        var challenge = new SignatureCheck.Challenge(catalog().getFirst(), "directCheck123");
        var lines = ProbeText.lines(challenge);
        assertEquals(
                challenge.profile().first().key(), ((TranslatableComponent) lines.get(0)).key());
        assertEquals(challenge.fallbackA(), ((TranslatableComponent) lines.get(0)).fallback());
        assertEquals(
                challenge.profile().second().key(), ((TranslatableComponent) lines.get(1)).key());
        assertEquals(challenge.profile().keybind(), ((KeybindComponent) lines.get(2)).keybind());
        assertEquals(challenge.nonce(), ((TextComponent) lines.get(3)).content());
    }
}
