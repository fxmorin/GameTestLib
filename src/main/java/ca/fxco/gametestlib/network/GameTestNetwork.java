package ca.fxco.gametestlib.network;

import ca.fxco.gametestlib.network.packets.GameTestPacket;
import ca.fxco.gametestlib.network.packets.ServerboundSetCheckStatePacket;
import ca.fxco.gametestlib.network.packets.ServerboundSetPulseStatePacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.function.Supplier;

import static ca.fxco.gametestlib.GameTestLibMod.id;

public class GameTestNetwork {

    // Clientbound = S2C
    // Serverbound = C2S

    private static final HashMap<Class<? extends GameTestPacket>, ResourceLocation> CLIENTBOUND_PACKET_TYPES = new HashMap<>();
    private static final HashMap<Class<? extends GameTestPacket>, ResourceLocation> SERVERBOUND_PACKET_TYPES = new HashMap<>();

    public static void initializeServer() {
        registerServerReceiver("pulse_state_block", ServerboundSetPulseStatePacket.class, ServerboundSetPulseStatePacket::new);
        registerServerReceiver("check_state_block", ServerboundSetCheckStatePacket.class, ServerboundSetCheckStatePacket::new);
    }

    public static void initializeClient() {
        // Add client receivers here
    }

    //
    // Registering Packets
    //

    private static <T extends GameTestPacket> void registerClientReceiver(String id, Class<T> type,
                                                                          Supplier<T> packetGen) {
        ResourceLocation resourceId = id(id);
        CLIENTBOUND_PACKET_TYPES.put(type, resourceId);
        ClientPlayNetworking.registerGlobalReceiver(resourceId, (client, handler, buf, packetSender) -> {
            T packet = packetGen.get();
            packet.read(buf);
            client.execute(() -> packet.handleClient(client, packetSender));
        });
    }

    private static <T extends GameTestPacket> void registerServerReceiver(String id, Class<T> type,
                                                                          Supplier<T> packetGen) {
        ResourceLocation resourceId = id(id);
        SERVERBOUND_PACKET_TYPES.put(type, resourceId);
        ServerPlayNetworking.registerGlobalReceiver(resourceId, (server, player, listener, buf, packetSender) -> {
            T packet = packetGen.get();
            packet.read(buf);
            server.execute(() -> packet.handleServer(server, player, packetSender));
        });
    }

    //
    // Sending Packets
    //

    @Environment(EnvType.CLIENT)
    public static void sendToServer(GameTestPacket packet) {
        ResourceLocation id = getPacketId(packet, EnvType.CLIENT);
        ClientPlayNetworking.send(id, packet.writeAsBuffer());
    }

    //
    // Validation
    //

    private static ResourceLocation getPacketId(GameTestPacket packet, EnvType envType) {
        ResourceLocation id = (envType == EnvType.SERVER ? CLIENTBOUND_PACKET_TYPES : SERVERBOUND_PACKET_TYPES).get(packet.getClass());
        if (id == null) {
            // Used to create the exception to throw, gets the other list to check if it's there
            ResourceLocation inWrongBounds = (envType != EnvType.SERVER ? CLIENTBOUND_PACKET_TYPES : SERVERBOUND_PACKET_TYPES).get(packet.getClass());
            if (inWrongBounds != null) {
                throw new IllegalArgumentException(
                        (envType == EnvType.SERVER ?
                                "Cannot send C2S packet to clients - " : "Cannot send S2C packet to server - ") +
                                packet.getClass().getSimpleName()
                );
            } else {
                throw new IllegalArgumentException("Invalid packet type!");
            }
        }
        return id;
    }
}
