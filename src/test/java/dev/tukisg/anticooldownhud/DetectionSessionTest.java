package dev.tukisg.anticooldownhud;

import static dev.tukisg.anticooldownhud.DetectionSession.Step.*;
import static dev.tukisg.anticooldownhud.SignatureCheckTest.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class DetectionSessionTest {
    private SignatureCheck.Challenge next() {
        return new SignatureCheck.Challenge(PROFILE, "freshNonce123");
    }

    @Test
    void requiresTwoFreshMatchingReplies() {
        var session = new DetectionSession(List.of(PROFILE), 1);
        assertEquals(CONFIRM, session.reply(CHALLENGE, match(CHALLENGE)));
        var fresh = next();
        assertEquals(DETECTED, session.reply(fresh, match(fresh)));
    }

    @Test
    void duplicateAndStaleRepliesDoNotConfirm() {
        var session = new DetectionSession(List.of(PROFILE), 1);
        assertEquals(CONFIRM, session.reply(CHALLENGE, match(CHALLENGE)));
        assertEquals(INCONCLUSIVE, session.reply(CHALLENGE, match(CHALLENGE)));
        assertEquals(INCONCLUSIVE, session.reply(next(), match(CHALLENGE)));
    }

    @Test
    void timeoutResetsConfirmationAndRetriesAreBounded() {
        var session = new DetectionSession(List.of(PROFILE), 1);
        assertEquals(CONFIRM, session.reply(CHALLENGE, match(CHALLENGE)));
        assertEquals(RETRY, session.timeout());
        assertEquals(CONFIRM, session.reply(next(), match(next())));
        assertEquals(INCONCLUSIVE, session.timeout());
    }

    @Test
    void conflictingConfirmationDoesNotBan() {
        var session = new DetectionSession(List.of(PROFILE), 1);
        assertEquals(CONFIRM, session.reply(CHALLENGE, match(CHALLENGE)));
        assertEquals(INCONCLUSIVE, session.reply(next(), vanilla(next())));
    }

    @Test
    void vanillaSkipsProfilesWithSameMissingKeyToSavePackets() throws Exception {
        var profiles = catalog();
        var session = new DetectionSession(profiles, 1);
        for (int i = 0; i < 3; i++) {
            assertEquals(
                    SignatureCheck.ProbeFormat.THREE_TRANSLATIONS, session.profile().probeFormat());
            var triple = new SignatureCheck.Challenge(session.profile(), "vanillaTriple" + i);
            assertEquals(NEXT, session.reply(triple, vanilla(triple)));
        }
        assertEquals("cooldownhud-combat", session.profile().id());
        var challenge = new SignatureCheck.Challenge(session.profile(), "vanilla123");
        assertEquals(NEXT, session.reply(challenge, vanilla(challenge)));
        assertEquals("freecam-legacy", session.profile().id());
        var freecam = new SignatureCheck.Challenge(session.profile(), "vanillaFreecam123");
        assertEquals(NEXT, session.reply(freecam, vanilla(freecam)));
        assertEquals("cooldownhud-memoryleakfix", session.profile().id());
        var nested = new SignatureCheck.Challenge(session.profile(), "vanillaNested123");
        assertEquals(NO_MATCH, session.reply(nested, vanilla(nested)));
    }

    @Test
    void missingFirstFamilyDoesNotSkipDifferentMods() {
        var other =
                new SignatureCheck.Profile(
                        "another", PROFILE.first(), PROFILE.second(), "key.another.open");
        var session = new DetectionSession(List.of(PROFILE, other), 1);
        assertEquals(NEXT, session.reply(CHALLENGE, vanilla(CHALLENGE)));
        assertEquals(other, session.profile());
    }

    @Test
    void translationlessOldVariantNeedsTwoFreshKeybindConfirmations() throws Exception {
        var profiles = catalog();
        var session = new DetectionSession(profiles, 1);
        for (int i = 0; i < 3; i++) {
            var triple = new SignatureCheck.Challenge(session.profile(), "legacyTriple" + i);
            assertEquals(NEXT, session.reply(triple, vanilla(triple)));
        }
        var initial = new SignatureCheck.Challenge(session.profile(), "initial12345");
        var lines = vanilla(initial);
        lines[2] = "H";
        assertEquals(NEXT, session.reply(initial, lines));
        assertEquals("cooldownhud-keybind", session.profile().id());
        var first = new SignatureCheck.Challenge(session.profile(), "fallback1234");
        var firstLines = vanilla(first);
        firstLines[2] = "H";
        assertEquals(CONFIRM, session.reply(first, firstLines));
        var second = new SignatureCheck.Challenge(session.profile(), "fallback5678");
        var secondLines = vanilla(second);
        secondLines[2] = "H";
        assertEquals(DETECTED, session.reply(second, secondLines));
    }

    @Test
    void alteredTranslationsFallThroughToOtherVerifiedSignatures() {
        var other =
                new SignatureCheck.Profile(
                        "other-pair",
                        new SignatureCheck.Translation("activity.anchor", Set.of("AutoAnchor")),
                        new SignatureCheck.Translation("activity.cart", Set.of("AutoCart")),
                        PROFILE.keybind());
        var session = new DetectionSession(List.of(PROFILE, other), 1);
        assertEquals(
                NEXT,
                session.reply(
                        CHALLENGE, new String[] {"changed", "AutoTotem", "H", CHALLENGE.nonce()}));
        var first = new SignatureCheck.Challenge(other, "firstMatch123");
        var second = new SignatureCheck.Challenge(other, "secondMatch123");
        assertEquals(
                CONFIRM,
                session.reply(first, new String[] {"AutoAnchor", "AutoCart", "H", first.nonce()}));
        assertEquals(
                DETECTED,
                session.reply(
                        second, new String[] {"AutoAnchor", "AutoCart", "H", second.nonce()}));
    }
}
