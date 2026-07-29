package ru.petus.connect;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Wire protocol between Petus Connect and the petus-auth server plugin.
 *
 * <p>The handshake happens in the play phase so that the same code works behind
 * Velocity, Paper and Folia without touching the login pipeline:
 *
 * <ol>
 *   <li>server sends {@code petus:challenge} with a random nonce,</li>
 *   <li>client answers {@code petus:ticket} with the launcher ticket,</li>
 *   <li>server replies {@code petus:result} and either keeps the player or kicks it.</li>
 * </ol>
 */
public final class PetusNet {
    public static final String PROTOCOL_VERSION = "3";

    private PetusNet() {
    }

    public record Challenge(String nonce, String serverId, int minProtocol) implements CustomPayload {
        public static final CustomPayload.Id<Challenge> ID =
                new CustomPayload.Id<>(Identifier.of("petus", "challenge"));
        public static final PacketCodec<PacketByteBuf, Challenge> CODEC =
                CustomPayload.codecOf(Challenge::write, Challenge::new);

        public Challenge(PacketByteBuf buf) {
            this(buf.readString(128), buf.readString(64), buf.readVarInt());
        }

        public void write(PacketByteBuf buf) {
            buf.writeString(nonce, 128);
            buf.writeString(serverId, 64);
            buf.writeVarInt(minProtocol);
        }

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record Ticket(String ticket, String nonce, String modVersion) implements CustomPayload {
        public static final CustomPayload.Id<Ticket> ID =
                new CustomPayload.Id<>(Identifier.of("petus", "ticket"));
        public static final PacketCodec<PacketByteBuf, Ticket> CODEC =
                CustomPayload.codecOf(Ticket::write, Ticket::new);

        public Ticket(PacketByteBuf buf) {
            this(buf.readString(4096), buf.readString(128), buf.readString(32));
        }

        public void write(PacketByteBuf buf) {
            buf.writeString(ticket, 4096);
            buf.writeString(nonce, 128);
            buf.writeString(modVersion, 32);
        }

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record Result(boolean accepted, String message) implements CustomPayload {
        public static final CustomPayload.Id<Result> ID =
                new CustomPayload.Id<>(Identifier.of("petus", "result"));
        public static final PacketCodec<PacketByteBuf, Result> CODEC =
                CustomPayload.codecOf(Result::write, Result::new);

        public Result(PacketByteBuf buf) {
            this(buf.readBoolean(), buf.readString(512));
        }

        public void write(PacketByteBuf buf) {
            buf.writeBoolean(accepted);
            buf.writeString(message, 512);
        }

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Challenge.ID, Challenge.CODEC);
        PayloadTypeRegistry.playS2C().register(Result.ID, Result.CODEC);
        PayloadTypeRegistry.playC2S().register(Ticket.ID, Ticket.CODEC);
    }
}
