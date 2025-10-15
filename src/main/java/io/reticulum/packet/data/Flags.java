package io.reticulum.packet.data;

import com.igormaznitsa.jbbp.io.JBBPBitInputStream;
import com.igormaznitsa.jbbp.io.JBBPBitNumber;
import com.igormaznitsa.jbbp.io.JBBPBitOutputStream;
import com.igormaznitsa.jbbp.io.JBBPByteOrder;
import com.igormaznitsa.jbbp.mapper.Bin;
import com.igormaznitsa.jbbp.mapper.BinType;
import io.reticulum.destination.DestinationType;
import io.reticulum.packet.HeaderType;
import io.reticulum.packet.PacketType;
import io.reticulum.packet.ContextType;
import io.reticulum.transport.TransportType;
import lombok.Data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Data
public class Flags {

    @Bin(name = "packettype", type = BinType.BIT, bitNumber = JBBPBitNumber.BITS_2, byteOrder = JBBPByteOrder.BIG_ENDIAN, order = 5)
    private PacketType packetType;

    @Bin(name = "destinationtype", type = BinType.BIT, bitNumber = JBBPBitNumber.BITS_2, byteOrder = JBBPByteOrder.BIG_ENDIAN, order = 7)
    private DestinationType destinationType;

    @Bin(name = "propagationtype", type = BinType.BIT, bitNumber = JBBPBitNumber.BITS_1, byteOrder = JBBPByteOrder.BIG_ENDIAN, order = 9)
    protected TransportType propagationType;

    @Bin(name = "contexttype", type = BinType.BIT, bitNumber = JBBPBitNumber.BITS_1, byteOrder = JBBPByteOrder.BIG_ENDIAN, order = 10)
    protected ContextType contextType;

    @Bin(name = "headertype", type = BinType.BIT, bitNumber = JBBPBitNumber.BITS_1, byteOrder = JBBPByteOrder.BIG_ENDIAN, order = 11)
    private HeaderType headerType;

    /**
     * IFAC Flag - if false - DISABLED
     */
    @Bin(name = "accesscodes", type = BinType.BIT, bitNumber = JBBPBitNumber.BITS_1, byteOrder = JBBPByteOrder.BIG_ENDIAN, order = 13)
    private boolean accessCodes;

    public Flags read(final JBBPBitInputStream In) throws IOException {
        this.packetType = PacketType.fromValue(In.readBitField(JBBPBitNumber.BITS_2));
        this.destinationType = DestinationType.fromValue(In.readBitField(JBBPBitNumber.BITS_2));
        this.propagationType = TransportType.fromValue(In.readBitField(JBBPBitNumber.BITS_1));
        this.contextType = ContextType.fromValue(In.readBitField(JBBPBitNumber.BITS_1));
        this.headerType = HeaderType.fromValue(In.readBitField(JBBPBitNumber.BITS_1));
        this.accessCodes = In.readBitField(JBBPBitNumber.BITS_1) == 1;

        return this;
    }

    public Flags write(final JBBPBitOutputStream Out) throws IOException {
        Out.writeBits(this.packetType.getValue(), JBBPBitNumber.BITS_2);
        Out.writeBits(this.destinationType.getValue(), JBBPBitNumber.BITS_2);
        Out.writeBits(this.propagationType.getValue(), JBBPBitNumber.BITS_1);
        Out.writeBits(this.contextType.getValue(), JBBPBitNumber.BITS_1);
        Out.writeBits(this.headerType.getValue(), JBBPBitNumber.BITS_1);
        Out.writeBits(this.accessCodes ? 1 : 0, JBBPBitNumber.BITS_1);

        return this;
    }

    public byte toByte() {
        try (var baos = new ByteArrayOutputStream()) {
            write(new JBBPBitOutputStream(baos));

            return baos.toByteArray()[0];
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Flags fromByte(final byte flags) {
        try (var baos = new ByteArrayOutputStream()) {
            baos.write(flags);

            return new Flags().read(new JBBPBitInputStream(new ByteArrayInputStream(baos.toByteArray())));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
