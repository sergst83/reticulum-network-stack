package io.reticulum.interfaces;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.ArrayUtils.isEmpty;

public interface KISS {
    int FEND = 0xC0;
    int FESC = 0xDB;
    int TFEND = 0xDC;
    int TFESC = 0xDD;
    int CMD_DATA = 0x00;
    int CMD_UNKNOWN = 0xFE;

    default byte[] escapeKiss(byte[] data) {
        var result = new byte[] {};
        if (nonNull(data) && data.length > 0) {
            try (
                    var is = new ByteArrayInputStream(data);
                    var buffer = new ByteArrayOutputStream()
            ) {
                while (is.available() > 0) {
                    var b = is.read();
                    if (b == FESC) {
                        buffer.write(FESC);
                        buffer.write(TFESC);
                    } else if (b == FEND) {
                        buffer.write(FESC);
                        buffer.write(TFEND);
                    } else {
                        buffer.write(b);
                    }
                }

                result = buffer.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return result;
    }

    default byte[] unmaskKiss(byte[] data) {
        if (isEmpty(data)) {
            return new byte[] {};
        }

        try(
                var os = new ByteArrayOutputStream();
                var is = new ByteArrayInputStream(data)
        ) {
            var escape = false;
            var command = CMD_UNKNOWN;
            while (is.available() > 0) {
                var aByte = is.read();
                if (os.size() == 0 && command == CMD_UNKNOWN) {
                    aByte = aByte & 0x0F;
                    command = aByte;
                } else if (command == CMD_DATA) {
                    if (aByte == FESC) {
                        escape = true;
                    } else {
                        if (escape) {
                            if (aByte == TFEND) {
                                aByte = FEND;
                            }
                            if (aByte == TFESC) {
                                aByte = FESC;
                            }
                            escape = false;
                        }
                        os.write(aByte);
                    }
                }
            }

            return os.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    default ByteBuf[] delimitersKiss() {
        return new ByteBuf[] {
                Unpooled.copyShort(FEND, CMD_DATA),
                Unpooled.copyShort(FEND),
        };
    }
}
