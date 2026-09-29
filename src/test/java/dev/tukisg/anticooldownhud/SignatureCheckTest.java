package dev.tukisg.anticooldownhud;

import static dev.tukisg.anticooldownhud.SignatureCheck.Result.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SignatureCheckTest {
    private static final String N = "challenge123", A = "fallback_a", B = "fallback_b";

    private SignatureCheck.Result check(String... lines) {
        return SignatureCheck.evaluate(lines, N, A, B);
    }

    @Test
    void vanillaIsNotDetected() {
        assertEquals(NO_MATCH, check(A, B, SignatureCheck.HUD_KEY, N));
    }

    @Test
    void englishModIsDetected() {
        assertEquals(DETECTED, check("AutoMace", "AutoTotem", "H", N));
    }

    @Test
    void russianModIsDetected() {
        assertEquals(DETECTED, check("Авто-булава (AutoMace)", "Авто-тотем (AutoTotem)", "H", N));
    }

    @Test
    void reassignedKeyStillMatches() {
        assertEquals(DETECTED, check("AutoMace", "AutoTotem", "Right Shift", N));
    }

    @Test
    void staleReplyIsIgnored() {
        assertEquals(INCONCLUSIVE, check("AutoMace", "AutoTotem", "H", "old"));
    }

    @Test
    void partialModMatchIsNotEnough() {
        assertEquals(INCONCLUSIVE, check("AutoMace", B, "H", N));
    }

    @Test
    void translationsWithoutKeyAreNotEnough() {
        assertEquals(INCONCLUSIVE, check("AutoMace", "AutoTotem", SignatureCheck.HUD_KEY, N));
    }

    @Test
    void keyWithoutTranslationsIsNotEnough() {
        assertEquals(INCONCLUSIVE, check(A, B, "H", N));
    }

    @Test
    void emptyMalformedAndMissingRepliesNeverDetect() {
        assertEquals(INCONCLUSIVE, check("", "", "", N));
        assertEquals(INCONCLUSIVE, check("AutoMace"));
        assertEquals(INCONCLUSIVE, SignatureCheck.evaluate(null, N, A, B));
    }

    @Test
    void manipulatedTextDoesNotMatch() {
        assertEquals(INCONCLUSIVE, check("AutoMace modified", "AutoTotem", "H", N));
    }
}
