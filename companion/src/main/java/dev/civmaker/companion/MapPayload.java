package dev.civmaker.companion;

import dev.civmaker.map.protocol.MapProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

record MapPayload(byte[] frame) implements CustomPacketPayload {
    static final Type<MapPayload> TYPE = new Type<>(Identifier.parse(MapProtocol.DATA));
    static final StreamCodec<RegistryFriendlyByteBuf, MapPayload> CODEC = CustomPacketPayload.codec(
            (payload, buffer) -> buffer.writeBytes(payload.frame()), buffer -> {
                int size = buffer.readableBytes();
                if (size < MapProtocol.HEADER_BYTES || size > MapProtocol.HEADER_BYTES + MapProtocol.CHUNK_BYTES) {
                    throw new IllegalArgumentException("Invalid CivMaker map packet size");
                }
                byte[] bytes = new byte[size];
                buffer.readBytes(bytes);
                return new MapPayload(bytes);
            });
    @Override public Type<MapPayload> type() { return TYPE; }

    record Hello() implements CustomPacketPayload {
        static final Type<Hello> TYPE = new Type<>(Identifier.parse(MapProtocol.HELLO));
        static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> buffer.writeInt(MapProtocol.VERSION), buffer -> {
                    if (buffer.readInt() != MapProtocol.VERSION) {
                        throw new IllegalArgumentException("Unsupported CivMaker map protocol");
                    }
                    return new Hello();
                });
        @Override public Type<Hello> type() { return TYPE; }
    }
}
