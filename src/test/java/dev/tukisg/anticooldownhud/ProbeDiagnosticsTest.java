package dev.tukisg.anticooldownhud;

import static dev.tukisg.anticooldownhud.SignatureCheckTest.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProbeDiagnosticsTest {
    @Test
    void wrapperFallbackIsDistinctFromLiteralFormatAndDoesNotExposeNonce() throws Exception {
        var profile =
                catalog().stream()
                        .filter(p -> p.id().equals("cooldownhud-memoryleakfix"))
                        .findFirst()
                        .orElseThrow();
        var challenge = new SignatureCheck.Challenge(profile, "wrapperNonce123");
        String fallback =
                ProbeDiagnostics.detail(
                        challenge,
                        new String[] {
                            challenge.wrapperFallback(),
                            challenge.wrapperFallback(),
                            challenge.wrapperFallback(),
                            challenge.nonce()
                        });
        assertTrue(fallback.contains("wrapper=fallback"));
        assertTrue(fallback.contains("<wrapperFallback>"));
        assertFalse(fallback.contains(challenge.nonce()));
        String literal =
                ProbeDiagnostics.detail(
                        challenge, new String[] {"%s", "%s", "%s", challenge.nonce()});
        assertTrue(literal.contains("wrapper=literal-format"));
        assertFalse(literal.contains("wrapper=fallback"));
    }

    @Test
    void wrapperFallbackCannotCountAsResolvedKey() throws Exception {
        var profile =
                catalog().stream()
                        .filter(p -> p.id().equals("cooldownhud-memoryleakfix"))
                        .findFirst()
                        .orElseThrow();
        var challenge = new SignatureCheck.Challenge(profile, "wrapperNonce123");
        var reply = match(challenge);
        reply[2] = challenge.wrapperFallback();
        assertEquals(SignatureCheck.Result.PARTIAL, challenge.evaluate(reply));
    }

    @Test
    void emptyReplyDoesNotClaimResolvedKey() {
        String detail =
                ProbeDiagnostics.detail(CHALLENGE, new String[] {"", "", "", CHALLENGE.nonce()});
        assertTrue(detail.contains("key=empty"));
        assertTrue(detail.contains("reply=[\"\", \"\", \"\"]"));
    }

    @Test
    void placeholdersAreReadableWithoutExposingNonce() {
        String detail =
                ProbeDiagnostics.detail(
                        CHALLENGE,
                        new String[] {
                            CHALLENGE.fallbackA(), "%s", "Unexpected", CHALLENGE.nonce()
                        });
        assertTrue(detail.contains("reply=[\"<fallbackA>\", \"%s\", \"Unexpected\"]"));
        assertFalse(detail.contains(CHALLENGE.nonce()));
        assertTrue(detail.contains("key=text"));
    }

    @Test
    void confirmedMatchDoesNotAddRawReplies() {
        assertFalse(ProbeDiagnostics.detail(CHALLENGE, match(CHALLENGE)).contains("reply="));
        assertFalse(ProbeDiagnostics.detail(CHALLENGE, vanilla(CHALLENGE)).contains("reply="));
    }

    @Test
    void clientTextCannotAddUnboundedOrFormattedLogLines() {
        String detail =
                ProbeDiagnostics.detail(
                        CHALLENGE,
                        new String[] {
                            "\"\\\u202e\u2028\u00a7a", "x".repeat(384), "H", CHALLENGE.nonce()
                        });
        assertTrue(detail.contains("\\\"\\\\\\u202e\\u2028\\u00a7a"));
        assertTrue(detail.contains("x".repeat(120) + "..."));
        assertFalse(detail.contains("x".repeat(121)));
    }
}
