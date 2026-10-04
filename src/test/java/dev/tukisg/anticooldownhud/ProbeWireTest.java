package dev.tukisg.anticooldownhud;

import static org.junit.jupiter.api.Assertions.*;

import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.protocol.nbt.serializer.DefaultNBTSerializer;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.util.adventure.AdventureNBTSerializer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;

import org.junit.jupiter.api.Test;

import java.io.*;

class ProbeWireTest {
    private static final AdventureNBTSerializer SERIALIZER =
            new AdventureNBTSerializer(ClientVersion.V_1_21_11, false);

    private static SignatureCheck.Challenge challenge() throws Exception {
        return new SignatureCheck.Challenge(
                SignatureCheckTest.catalog().stream()
                        .filter(p -> p.id().equals("cooldownhud-memoryleakfix"))
                        .findFirst()
                        .orElseThrow(),
                "wireNonce123");
    }

    private static NBTCompound packet(SignatureCheck.Challenge challenge) {
        var messages = NBTList.createCompoundList();
        for (Component line : ProbeText.lines(challenge)) {
            NBT value = SERIALIZER.serialize(line);
            if (value instanceof NBTCompound compound) messages.addTag(compound);
            else {
                var literal = new NBTCompound();
                literal.setTag("text", value);
                messages.addTag(literal);
            }
        }
        var front = new NBTCompound();
        front.setTag("messages", messages);
        front.setTag("color", new NBTString("black"));
        var packet = new NBTCompound();
        packet.setTag("front_text", front);
        packet.setTag("is_waxed", new NBTByte((byte) 0));
        return packet;
    }

    private static NBTList<NBTCompound> messages(NBTCompound packet) {
        return packet.getCompoundTagOrThrow("front_text")
                .getTagListOfTypeOrThrow("messages", NBTCompound.class);
    }

    private static void assertIntact(NBTCompound packet, SignatureCheck.Challenge challenge) {
        var messages = messages(packet);
        var expected = ProbeText.lines(challenge);
        assertEquals(4, messages.size());
        for (int i = 0; i < 4; i++)
            assertEquals(expected.get(i), SERIALIZER.deserialize(messages.getTag(i)));
        for (int i = 0; i < 3; i++) {
            var text = (TranslatableComponent) SERIALIZER.deserialize(messages.getTag(i));
            assertEquals(1, text.arguments().size());
        }
    }

    @Test
    void unchangedPacketNeedsNoRewrite() throws Exception {
        var challenge = challenge();
        var packet = packet(challenge);
        var result = ProbeWire.verify(packet, challenge);
        assertEquals("intact", result.status());
        assertSame(packet, result.data());
    }

    @Test
    void missingArgumentsAreRestoredWithoutMutatingOriginal() throws Exception {
        var challenge = challenge();
        var packet = packet(challenge);
        for (int i = 0; i < 3; i++) messages(packet).getTag(i).removeTag("with");
        var result = ProbeWire.verify(packet, challenge);
        assertEquals("repaired", result.status());
        assertFalse(messages(packet).getTag(0).contains("with"));
        assertIntact(result.data(), challenge);
        assertEquals(packet.getTagOrThrow("is_waxed"), result.data().getTagOrThrow("is_waxed"));
        assertEquals(
                "black",
                result.data()
                        .getCompoundTagOrThrow("front_text")
                        .getStringTagValueOrThrow("color"));
    }

    @Test
    void flattenedPercentSRepliesAreReplacedWithNestedComponents() throws Exception {
        var challenge = challenge();
        var packet = packet(challenge);
        var strings = NBTList.createStringList();
        for (String line : new String[] {"%s", "%s", "%s", challenge.nonce()})
            strings.addTag(new NBTString(line));
        packet.getCompoundTagOrThrow("front_text").setTag("messages", strings);
        var result = ProbeWire.verify(packet, challenge);
        assertEquals("repaired", result.status());
        assertIntact(result.data(), challenge);
    }

    @Test
    void brokenFilteredTextIsRepairedAsWell() throws Exception {
        var challenge = challenge();
        var packet = packet(challenge);
        var filtered = messages(packet).copy();
        filtered.getTag(1).removeTag("with");
        packet.getCompoundTagOrThrow("front_text").setTag("filtered_messages", filtered);
        var result = ProbeWire.verify(packet, challenge);
        assertEquals("repaired", result.status());
        assertEquals(
                messages(result.data()),
                result.data()
                        .getCompoundTagOrThrow("front_text")
                        .getTagOrThrow("filtered_messages"));
    }

    @Test
    void unrelatedSignAndStaleNonceAreNeverRewritten() throws Exception {
        var challenge = challenge();
        var other = new SignatureCheck.Challenge(challenge.profile(), "otherNonce123");
        var packet = packet(other);
        var result = ProbeWire.verify(packet, challenge);
        assertEquals("unrelated", result.status());
        assertSame(packet, result.data());
        assertEquals("missing", ProbeWire.verify(null, challenge).status());
        assertEquals("missing-front", ProbeWire.verify(new NBTCompound(), challenge).status());
    }

    @Test
    void binaryNbtRoundTripPreservesNestedArgumentsAndNonce() throws Exception {
        var challenge = challenge();
        var packet = packet(challenge);
        messages(packet).getTag(0).removeTag("with");
        var repaired = ProbeWire.verify(packet, challenge).data();
        var bytes = new ByteArrayOutputStream();
        DefaultNBTSerializer.INSTANCE.serializeTag(new DataOutputStream(bytes), repaired, false);
        var decoded =
                (NBTCompound)
                        DefaultNBTSerializer.INSTANCE.deserializeTag(
                                NBTLimiter.noop(),
                                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                                false);
        assertIntact(decoded, challenge);
        assertEquals("intact", ProbeWire.verify(decoded, challenge).status());
        assertTrue(bytes.size() < 4096);
    }

    @Test
    void literalAndWrapperFallbackRepliesNeverConfirmBan() throws Exception {
        var challenge = challenge();
        for (String reply : new String[] {"%s", challenge.wrapperFallback()}) {
            for (int i = 0; i < 2; i++) {
                var session = new DetectionSession(java.util.List.of(challenge.profile()), 1);
                var fresh = new SignatureCheck.Challenge(challenge.profile(), "negativeNonce" + i);
                String text = reply.equals("%s") ? reply : fresh.wrapperFallback();
                var step = session.reply(fresh, new String[] {text, text, text, fresh.nonce()});
                assertNotEquals(DetectionSession.Step.CONFIRM, step);
                assertNotEquals(DetectionSession.Step.DETECTED, step);
            }
        }
    }
}
