package dev.tukisg.anticooldownhud;

import static dev.tukisg.anticooldownhud.SignatureCheckTest.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProbeDiagnosticsTest {
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
