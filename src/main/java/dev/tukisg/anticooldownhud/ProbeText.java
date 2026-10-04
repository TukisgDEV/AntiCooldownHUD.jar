package dev.tukisg.anticooldownhud;

import net.kyori.adventure.text.Component;

import java.util.List;

final class ProbeText {
    static List<Component> lines(SignatureCheck.Challenge challenge) {
        var profile = challenge.profile();
        return List.of(
                wrap(
                        challenge,
                        Component.translatable(profile.first().key(), challenge.fallbackA())),
                wrap(
                        challenge,
                        Component.translatable(profile.second().key(), challenge.fallbackB())),
                wrap(challenge, Component.keybind(profile.keybind())),
                Component.text(challenge.nonce()));
    }

    private static Component wrap(SignatureCheck.Challenge challenge, Component content) {
        return challenge.profile().probeFormat() == SignatureCheck.ProbeFormat.TRANSLATION_ARGUMENT
                ? Component.translatable("options.value", challenge.wrapperFallback())
                        .arguments(content)
                : content;
    }

    private ProbeText() {}
}
