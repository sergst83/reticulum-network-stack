package io.reticulum.packet.data;

import com.igormaznitsa.jbbp.io.JBBPBitInputStream;
import com.igormaznitsa.jbbp.io.JBBPBitOutputStream;
import com.igormaznitsa.jbbp.io.JBBPByteOrder;
import com.igormaznitsa.jbbp.mapper.Bin;
import com.igormaznitsa.jbbp.mapper.BinType;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import java.io.IOException;

/**
 * The HEADER field is 2 bytes long.
 * Byte 1: [IFAC Flag], [Header Type], [Propagation Type], [Destination Type] and [Packet Type]
 * Byte 2: Number of hops
 */
@Data
@ToString(exclude = {"root"})
@RequiredArgsConstructor
public class Header {
    private final DataPacket root;

    /**
     * <pre>
     * {@code
     * 00000000
     * || | | |
     * || | | +------- Packet Type       = DATA
     * || | +--------- Destination Type  = SINGLE
     * || +----------- Propagation Type  = BROADCAST
     * |+------------- Header Type       = HEADER_1 (two byte header, one address field)
     * +-------------- Access Codes      = DISABLED
     * }
     * </pre>
     */
    @Bin(name = "flags", byteOrder = JBBPByteOrder.BIG_ENDIAN, order = 4)
    private Flags flags;

    /**
     * 0 - 128
     */
    @Bin(name = "hops", type = BinType.BYTE, byteOrder = JBBPByteOrder.BIG_ENDIAN, order = 17)
    private byte hops;

    public Header read(final JBBPBitInputStream In) throws IOException {
        if (this.flags == null) {
            this.flags = new Flags();
        }
        this.flags.read(In);
        this.hops = (byte) In.readByte();

        return this;
    }

    public Header write(final JBBPBitOutputStream Out) throws IOException {
        flags.write(Out);
        Out.write(this.hops);

        return this;
    }
}
