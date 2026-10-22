// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VIS33552 (VIASR)

import dev.vis33552.handshakefix.PayloadOptionalizer;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.negotiation.NegotiableNetworkComponent;
import net.neoforged.neoforge.network.negotiation.NegotiationResult;
import net.neoforged.neoforge.network.negotiation.NetworkComponentNegotiator;
import net.neoforged.neoforge.network.payload.ModdedNetworkQueryPayload;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;
import net.neoforged.neoforge.network.registration.PayloadRegistration;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Headless smoke test for the core patch: registers a fake required payload the same way a mod
 * would, runs PayloadOptionalizer, then checks that the registration became optional.
 */
public final class Probe {

    public record FakePayload(Identifier id) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<FakePayload> TYPE =
                new CustomPacketPayload.Type<>(Identifier.parse("probemod:test_channel"));

        @Override
        public CustomPacketPayload.Type<FakePayload> type() {
            return TYPE;
        }
    }

    public static void main(String[] args) throws Exception {
        StreamCodec<FriendlyByteBuf, FakePayload> codec = StreamCodec.of(
                (buf, value) -> buf.writeIdentifier(value.id()),
                buf -> new FakePayload(buf.readIdentifier()));
        IPayloadHandler<FakePayload> handler = (payload, context) -> {
        };

        NetworkRegistry.register(
                FakePayload.TYPE,
                codec,
                handler,
                handler,
                List.of(ConnectionProtocol.PLAY),
                Optional.of(PacketFlow.SERVERBOUND),
                "1",
                false); // required, exactly like a normal mod

        Map<Identifier, PayloadRegistration<?>> registrations = dump();
        PayloadRegistration<?> before = registrations.get(FakePayload.TYPE.id());
        if (before == null) {
            throw new IllegalStateException("test payload was not registered at all");
        }
        System.out.println("[probe] before: optional=" + before.optional() + " version=" + before.version());

        int changed = PayloadOptionalizer.apply();
        System.out.println("[probe] patch changed " + changed + " registration(s)");

        PayloadRegistration<?> after = dump().get(FakePayload.TYPE.id());
        System.out.println("[probe] after: optional=" + after.optional());
        if (!after.optional()) {
            throw new IllegalStateException("PATCH FAILED: registration is still required");
        }
        if (before == after) {
            throw new IllegalStateException("PATCH FAILED: registration object was not replaced");
        }
        System.out.println("[probe] OK - required channel downgraded to optional");

        // ---- 2) the query the client actually sends to the server must carry optional=true ----
        Map<Object, Map<Identifier, PayloadRegistration<?>>> all = dumpAll();
        @SuppressWarnings("unchecked")
        Map<ConnectionProtocol, Map<Identifier, PayloadRegistration<?>>> typed =
                (Map<ConnectionProtocol, Map<Identifier, PayloadRegistration<?>>>) (Map<?, ?>) all;
        ModdedNetworkQueryPayload query = ModdedNetworkQueryPayload.fromRegistry(typed);
        boolean optionalInQuery = query.queries().get(ConnectionProtocol.PLAY).stream()
                .filter(c -> c.id().equals(FakePayload.TYPE.id()))
                .findFirst()
                .orElseThrow()
                .optional();
        System.out.println("[probe] query component optional=" + optionalInQuery);
        if (!optionalInQuery) {
            throw new IllegalStateException("QUERY STILL MARKS THE CHANNEL AS REQUIRED");
        }

        // ---- 3) negotiation against a server that does not have the mod ----
        NegotiableNetworkComponent optionalComponent = new NegotiableNetworkComponent(after);
        NegotiableNetworkComponent requiredComponent =
                new NegotiableNetworkComponent(FakePayload.TYPE.id(), "1", Optional.of(PacketFlow.SERVERBOUND), false);

        boolean optionalPasses = negotiate(optionalComponent) != null;
        boolean requiredFails = negotiate(requiredComponent) == null;
        System.out.println("[probe] optional vs empty server => success=" + optionalPasses);
        System.out.println("[probe] required vs empty server => success=" + (!requiredFails));
        if (!optionalPasses || !requiredFails) {
            throw new IllegalStateException("NEGOTIATION BEHAVIOUR CHANGED");
        }

        // ---- 4) what the SERVER ends up with: the channel is not in its payload setup at all ----
        NegotiationResult result = negotiate(optionalComponent);
        NetworkPayloadSetup setup = NetworkPayloadSetup.from(Map.of(ConnectionProtocol.PLAY, result));
        System.out.println("[probe] server-side setup channels=" + setup.getChannels(ConnectionProtocol.PLAY).keySet());
        if (setup.getChannel(ConnectionProtocol.PLAY, FakePayload.TYPE.id()) != null) {
            throw new IllegalStateException("SERVER STILL SEES THE CHANNEL");
        }
        System.out.println("[probe] OK - for the server the mod simply does not exist (no channel, no handler)");
    }

    /** @return negotiation result, or {@code null} when negotiation failed/exploded */
    private static NegotiationResult negotiate(NegotiableNetworkComponent clientSide) {
        try {
            NegotiationResult result = NetworkComponentNegotiator.negotiate(List.of(clientSide), List.of());
            return result.success() ? result : null;
        } catch (Throwable t) {
            // the negotiator touches ModList only when building failure reasons
            System.out.println("[probe] negotiate threw (treated as failure): " + t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Map<Identifier, PayloadRegistration<?>>> dumpAll() throws Exception {
        Class<?> registry = Class.forName("net.neoforged.neoforge.network.registration.NetworkRegistry");
        Field field = registry.getDeclaredField("PAYLOAD_REGISTRATIONS");
        field.setAccessible(true);
        return (Map<Object, Map<Identifier, PayloadRegistration<?>>>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<Identifier, PayloadRegistration<?>> dump() throws Exception {
        return (Map<Identifier, PayloadRegistration<?>>) (Map<?, ?>) dumpAll().get(ConnectionProtocol.PLAY);
    }
}
