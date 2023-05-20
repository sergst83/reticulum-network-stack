package io.reticulum.packet.data;

import com.igormaznitsa.jbbp.io.JBBPBitInputStream;
import com.igormaznitsa.jbbp.io.JBBPBitOutputStream;
import com.igormaznitsa.jbbp.io.JBBPByteOrder;
import com.igormaznitsa.jbbp.mapper.Bin;
import com.igormaznitsa.jbbp.mapper.BinType;
import io.reticulum.packet.HeaderType;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.io.IOException;

import static io.reticulum.packet.data.DataPacket.assrtExprNotNeg;

@Data
@RequiredArgsConstructor
public class Addresses {
    private final DataPacket root;

    @Bin(name = "hash1", type = BinType.BYTE_ARRAY, byteOrder = JBBPByteOrder.BIG_ENDIAN, order = 23)
    byte[] hash1;

    @Bin(name = "hash2", type = BinType.BYTE_ARRAY, byteOrder = JBBPByteOrder.BIG_ENDIAN, order = 25)
    byte[] hash2;

    public Addresses read(final JBBPBitInputStream In) throws IOException {
        this.hash1 = In.readByteArray(16, JBBPByteOrder.BIG_ENDIAN);
        if (root.getHeader().getFlags().getHeaderType() == HeaderType.HEADER_2) {
            this.hash2 = In.readByteArray(assrtExprNotNeg((root.getHeader().getFlags().getHeaderType().getValue() * 16)), JBBPByteOrder.BIG_ENDIAN);
        }

        return this;
    }

    public Addresses write(final JBBPBitOutputStream Out) throws IOException {
        Out.writeBytes(this.hash1, 16, JBBPByteOrder.BIG_ENDIAN);
        if (root.getHeader().getFlags().getHeaderType() == HeaderType.HEADER_2) {
            Out.writeBytes(this.hash2, assrtExprNotNeg((root.getHeader().getFlags().getHeaderType().getValue() * 16)), JBBPByteOrder.BIG_ENDIAN);
        }

        return this;
    }
}
