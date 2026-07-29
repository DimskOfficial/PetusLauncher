package ru.petus.connect;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Client half of the Petus launcher handshake. It holds no game logic at all: it
 * waits for the server challenge and answers with the launcher ticket.
 */
public final class PetusConnect implements ClientModInitializer {
    public static final String MOD_VERSION = "3.0.0";
    private static final Logger LOGGER = LoggerFactory.getLogger("petus-connect");

    @Override
    public void onInitializeClient() {
        PetusNet.register();

        ClientPlayNetworking.registerGlobalReceiver(PetusNet.Challenge.ID, (payload, context) -> {
            Optional<TicketSource.Ticket> ticket = TicketSource.find();
            String value = ticket.map(TicketSource.Ticket::value).orElse("");
            if (value.isEmpty()) {
                LOGGER.warn("Server {} asked for a Petus ticket, but this game was not started by PetusLauncher",
                        payload.serverId());
            } else {
                LOGGER.info("Answering the Petus handshake for server {}", payload.serverId());
            }
            context.responseSender().sendPacket(
                    new PetusNet.Ticket(value, payload.nonce(), MOD_VERSION));
        });

        ClientPlayNetworking.registerGlobalReceiver(PetusNet.Result.ID, (payload, context) -> {
            if (payload.accepted()) {
                LOGGER.info("Petus handshake accepted: {}", payload.message());
            } else {
                LOGGER.error("Petus handshake rejected: {}", payload.message());
            }
        });

        LOGGER.info("Petus Connect {} ready (protocol {})", MOD_VERSION, PetusNet.PROTOCOL_VERSION);
    }
}
