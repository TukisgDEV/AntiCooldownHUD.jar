package dev.tukisg.anticooldownhud;

final class ProbeDiagnostics {
    static String detail(SignatureCheck.Challenge challenge, String[] lines) {
        var profile = challenge.profile();
        String key =
                lines[2].isBlank()
                        ? "empty"
                        : profile.keybind().equals(lines[2]) ? "missing" : "text";
        String detail =
                profile.id()
                        + ", first="
                        + profile.first().values().contains(lines[0])
                        + ", second="
                        + profile.second().values().contains(lines[1])
                        + ", key="
                        + key;
        if (challenge.evaluate(lines) == SignatureCheck.Result.PARTIAL) {
            if (challenge.profile().probeFormat()
                    == SignatureCheck.ProbeFormat.TRANSLATION_ARGUMENT) {
                boolean wrapperFallback = false;
                boolean formatLiteral = false;
                for (int i = 0; i < 3; i++) {
                    wrapperFallback |= challenge.wrapperFallback().equals(lines[i]);
                    formatLiteral |= "%s".equals(lines[i]);
                }
                if (wrapperFallback) detail += ", wrapper=fallback";
                else if (formatLiteral) detail += ", wrapper=literal-format";
            }
            detail +=
                    ", reply=["
                            + quote(lines[0], challenge)
                            + ", "
                            + quote(lines[1], challenge)
                            + ", "
                            + quote(lines[2], challenge)
                            + "]";
        }
        return detail;
    }

    private static String quote(String value, SignatureCheck.Challenge challenge) {
        value =
                value.replace(challenge.fallbackA(), "<fallbackA>")
                        .replace(challenge.fallbackB(), "<fallbackB>")
                        .replace(challenge.wrapperFallback(), "<wrapperFallback>")
                        .replace(challenge.nonce(), "<nonce>");
        StringBuilder result = new StringBuilder("\"");
        int limit = Math.min(value.length(), 120);
        for (int i = 0; i < limit; i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') result.append('\\').append(c);
            else if (Character.isISOControl(c)
                    || Character.getType(c) == Character.FORMAT
                    || Character.getType(c) == Character.LINE_SEPARATOR
                    || Character.getType(c) == Character.PARAGRAPH_SEPARATOR
                    || c == '\u00a7') result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        if (value.length() > limit) result.append("...");
        return result.append('"').toString();
    }

    private ProbeDiagnostics() {}
}
