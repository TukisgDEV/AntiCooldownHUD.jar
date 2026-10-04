package dev.tukisg.anticooldownhud;

import static dev.tukisg.anticooldownhud.SignatureCheckTest.*;

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

class MemoryLeakFixLocalizationTest {
    private static SignatureCheck.Profile profile() throws Exception {
        return catalog().stream()
                .filter(p -> p.id().equals("cooldownhud-memoryleakfix-localization"))
                .findFirst()
                .orElseThrow();
    }

    private static Properties translations(String language) throws Exception {
        var result = new Properties();
        try (var reader =
                new InputStreamReader(
                        MemoryLeakFixLocalizationTest.class.getResourceAsStream(
                                "/fixtures/memoryleakfix-" + language + ".properties"),
                        StandardCharsets.UTF_8)) {
            result.load(reader);
        }
        return result;
    }

    private static boolean canTranslate(String key, Properties mod, boolean serverLanguage) {
        if (serverLanguage) return key.startsWith("activity.") && mod.containsKey(key);
        return key.equals("options.value");
    }

    private static String render(Component component, Properties mod, boolean serverLanguage) {
        if (component instanceof TextComponent text) return text.content();
        if (component instanceof KeybindComponent key) {
            return canTranslate(key.keybind(), mod, serverLanguage)
                    ? mod.getProperty(key.keybind(), key.keybind())
                    : key.keybind();
        }
        var text = (TranslatableComponent) component;
        String fallback = text.fallback() == null ? text.key() : text.fallback();
        if (!canTranslate(text.key(), mod, serverLanguage)) return fallback;
        if (text.key().equals("options.value")) {
            Component argument = (Component) text.arguments().getFirst().value();
            if (argument instanceof KeybindComponent key)
                return mod.getProperty(key.keybind(), key.keybind());
            var nested = (TranslatableComponent) argument;
            return mod.getProperty(nested.key(), nested.fallback());
        }
        return mod.getProperty(text.key(), fallback);
    }

    private static String[] reply(
            SignatureCheck.Challenge challenge, Properties mod, boolean serverLanguage) {
        return ProbeText.lines(challenge).stream()
                .map(c -> render(c, mod, serverLanguage))
                .toArray(String[]::new);
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "ru_ru"})
    void reproducesObservedWrapperFallbackAndDetectsThroughActivityLanguageOverride(String language)
            throws Exception {
        var mod = translations(language);
        var old =
                catalog().stream()
                        .filter(p -> p.id().equals("cooldownhud-memoryleakfix"))
                        .findFirst()
                        .orElseThrow();
        var challenge = new SignatureCheck.Challenge(old, "observedNonce123");
        assertArrayEquals(
                new String[] {
                    challenge.wrapperFallback(),
                    challenge.wrapperFallback(),
                    challenge.wrapperFallback(),
                    challenge.nonce()
                },
                reply(challenge, mod, true));
        assertEquals(
                SignatureCheck.Result.PARTIAL, challenge.evaluate(reply(challenge, mod, true)));
        var direct = new SignatureCheck.Challenge(profile(), "localization123");
        assertEquals(SignatureCheck.Result.DETECTED, direct.evaluate(reply(direct, mod, true)));
        assertEquals(SignatureCheck.Action.BAN, direct.profile().action());
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "ru_ru"})
    void fullSessionReachesThreeTranslationsAfterMissingKeyAndRequiresFreshConfirmation(
            String language) throws Exception {
        var session = new DetectionSession(catalog(), 1);
        var mod = translations(language);
        int confirmations = 0;
        for (int i = 0; i < catalog().size() + 2; i++) {
            var challenge = new SignatureCheck.Challenge(session.profile(), "sessionLocal" + i);
            var step = session.reply(challenge, reply(challenge, mod, true));
            if (step == DetectionSession.Step.CONFIRM) confirmations++;
            else if (step == DetectionSession.Step.DETECTED) {
                assertEquals(1, confirmations);
                assertEquals(profile(), challenge.profile());
                return;
            } else assertEquals(DetectionSession.Step.NEXT, step);
        }
        fail("The localization profile was not reached");
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "ru_ru"})
    void defaultLanguagePathStillUsesExistingNestedDetection(String language) throws Exception {
        var old =
                catalog().stream()
                        .filter(p -> p.id().equals("cooldownhud-memoryleakfix"))
                        .findFirst()
                        .orElseThrow();
        var challenge = new SignatureCheck.Challenge(old, "defaultNonce123");
        assertEquals(
                SignatureCheck.Result.DETECTED,
                challenge.evaluate(reply(challenge, translations(language), false)));
    }

    @Test
    void cleanClientAndClientSpooferAloneNeverMatch() throws Exception {
        var challenge = new SignatureCheck.Challenge(profile(), "cleanNonce123");
        var noMod = new Properties();
        noMod.setProperty("memoryleakfix.name", "MemoryLeakFix");
        noMod.setProperty("clientspoofer.name", "Client Spoofer");
        for (boolean serverLanguage : new boolean[] {false, true}) {
            assertEquals(
                    SignatureCheck.Result.NO_MATCH,
                    challenge.evaluate(reply(challenge, noMod, serverLanguage)));
        }
    }

    @Test
    void twoTranslationsGenericKeyAndPlaceholdersNeverCompleteSignature() throws Exception {
        var challenge = new SignatureCheck.Challenge(profile(), "partialNonce123");
        for (String third :
                List.of(
                        "H",
                        "%s",
                        "",
                        challenge.fallbackC(),
                        challenge.wrapperFallback(),
                        "AutoAnchor suffix")) {
            assertEquals(
                    SignatureCheck.Result.PARTIAL,
                    challenge.evaluate(
                            new String[] {"AutoMace", "AutoTotem", third, challenge.nonce()}));
        }
    }

    @Test
    void replayAndConflictingConfirmationDoNotBan() throws Exception {
        var session = new DetectionSession(List.of(profile()), 1);
        var first = new SignatureCheck.Challenge(profile(), "firstLocal123");
        var second = new SignatureCheck.Challenge(profile(), "secondLocal123");
        assertEquals(DetectionSession.Step.CONFIRM, session.reply(first, match(first)));
        assertEquals(DetectionSession.Step.INCONCLUSIVE, session.reply(first, match(first)));
        assertEquals(DetectionSession.Step.INCONCLUSIVE, session.reply(second, match(first)));
        assertEquals(DetectionSession.Step.INCONCLUSIVE, session.reply(second, vanilla(second)));
    }

    @Test
    void profileCannotDisableTranslationsOrReuseKeys() throws Exception {
        var p = profile();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SignatureCheck.Profile(
                                p.id(),
                                p.first(),
                                p.second(),
                                null,
                                false,
                                p.modName(),
                                p.action(),
                                p.probeFormat(),
                                p.third()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SignatureCheck.Profile(
                                p.id(),
                                p.first(),
                                p.second(),
                                null,
                                true,
                                p.modName(),
                                p.action(),
                                p.probeFormat(),
                                p.first()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SignatureCheck.Profile(
                                p.id(),
                                p.first(),
                                p.second(),
                                null,
                                true,
                                p.modName(),
                                p.action(),
                                p.probeFormat(),
                                null));
    }
}
