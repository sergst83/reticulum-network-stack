package io.reticulum.packet.data;

import com.igormaznitsa.jbbp.io.JBBPBitInputStream;
import com.igormaznitsa.jbbp.io.JBBPBitOutputStream;
import lombok.NonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class DataPacketConverter {

    public static DataPacket fromBytes(@NonNull byte[] packet) throws IOException {
        try (var bais = new ByteArrayInputStream(packet)) {
            return new DataPacket().read(new JBBPBitInputStream(bais));
        }
    }

    public static byte[] toBytes(@NonNull DataPacket dataPacket) throws IOException {
        try (var baos = new ByteArrayOutputStream()) {
            dataPacket.write(new JBBPBitOutputStream(baos));

            return baos.toByteArray();
        }
    }
}
