package io.reticulum.packet.data;

import com.igormaznitsa.jbbp.io.JBBPBitInputStream;
import com.igormaznitsa.jbbp.io.JBBPBitOutputStream;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public class DataPacketConverter {

    @SneakyThrows
    public static DataPacket fromBytes(@NonNull byte[] packet) {
        try (var bais = new ByteArrayInputStream(packet)) {
            return new DataPacket().read(new JBBPBitInputStream(bais));
        }
    }

    @SneakyThrows
    public static byte[] toBytes(@NonNull DataPacket dataPacket) {
        try (var baos = new ByteArrayOutputStream()) {
            dataPacket.write(new JBBPBitOutputStream(baos));

            return baos.toByteArray();
        }
    }
}
