package io.reticulum.packet.data;

import com.igormaznitsa.jbbp.io.JBBPBitInputStream;
import com.igormaznitsa.jbbp.io.JBBPBitOutputStream;
import com.igormaznitsa.jbbp.io.JBBPByteOrder;
import com.igormaznitsa.jbbp.mapper.Bin;
import com.igormaznitsa.jbbp.mapper.BinType;
import io.reticulum.packet.PacketContextType;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.io.IOException;

@Data
@RequiredArgsConstructor
public class DataPacket {

    public DataPacket() {
        this(0);
    }

    @Bin(name = "ifac_size", byteOrder = JBBPByteOrder.BIG_ENDIAN, order = 0)
    private final int ifac_size;

    @Bin(name = "header", byteOrder = JBBPByteOrder.BIG_ENDIAN, order = 3)
    private Header header;

    @Bin(name = "ifac", type = BinType.BYTE_ARRAY, byteOrder = JBBPByteOrder.BIG_ENDIAN, order = 20)
    private byte[] ifac;

    @Bin(name = "addresses", byteOrder = JBBPByteOrder.BIG_ENDIAN, order = 22)
    private Addresses addresses;

    @Bin(name = "context", type = BinType.UBYTE, byteOrder = JBBPByteOrder.BIG_ENDIAN, order = 29)
    private PacketContextType context;

    @Bin(name = "data", type = BinType.BYTE_ARRAY, byteOrder = JBBPByteOrder.BIG_ENDIAN, order = 30)
    private byte[] data;

    public DataPacket read(final JBBPBitInputStream In) throws IOException {
        if (this.header == null) {
            this.header = new Header(this);
        }
        this.header.read(In);
        if (this.header.getFlags().isAccessCodes()) {
            this.ifac = In.readByteArray(assrtExprNotNeg(this.ifac_size), JBBPByteOrder.BIG_ENDIAN);
        }
        if (this.addresses == null) {
            this.addresses = new Addresses(this);
        }
        this.addresses.read(In);
        this.context = PacketContextType.fromValue((byte) In.readByte());
        this.data = In.readByteArray(-1, JBBPByteOrder.BIG_ENDIAN);

        return this;
    }

    public DataPacket write(final JBBPBitOutputStream Out) throws IOException {
        header.write(Out);
        if (this.header.getFlags().isAccessCodes()) {
            Out.writeBytes(this.ifac, this.ifac.length, JBBPByteOrder.BIG_ENDIAN);
        }
        addresses.write(Out);
        Out.write(this.context.getValue());
        Out.writeBytes(this.data, this.data.length, JBBPByteOrder.BIG_ENDIAN);

        return this;
    }

    static int assrtExprNotNeg(final int value) {
        if (value < 0) throw new IllegalArgumentException("Negative value in expression");
        return value;
    }
}
