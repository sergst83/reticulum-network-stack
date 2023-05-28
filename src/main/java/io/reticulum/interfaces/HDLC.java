package io.reticulum.interfaces;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.apache.commons.lang3.ArrayUtils.isEmpty;
import static org.apache.commons.lang3.ArrayUtils.isNotEmpty;

public interface HDLC {
    byte FLAG = 0x7E;
    byte ESC = 0x7D;
    byte ESC_MASK = 0x20;

    default byte[] escapeHdlc(byte[] data) {
        if (isNotEmpty(data)) {
            try (var buffer = new ByteArrayOutputStream()) {
                for (byte aByte : data) {
                    if (aByte == ESC) {
                        buffer.write(ESC);
                        buffer.write(ESC ^ ESC_MASK);
                    } else if (aByte == FLAG) {
                        buffer.write(ESC);
                        buffer.write(FLAG ^ ESC_MASK);
                    } else {
                        buffer.write(aByte);
                    }
                }
                return buffer.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return new byte[] {};
    }

    default byte[] unmaskHdlc(byte[] data) {
        if (isEmpty(data)) {
            return new byte[] {};
        }

        try(
                var os = new ByteArrayOutputStream();
                var is = new ByteArrayInputStream(data)
        ) {
            var escape = false;
            while (is.available() > 0) {
                var aByte = is.read();
                if (aByte == ESC) {
                    escape = true;
                } else {
                    if (escape) {
                        if (aByte == (FLAG ^ ESC_MASK)) {
                            aByte = FLAG;
                        }
                        if (aByte == (ESC ^ ESC_MASK)) {
                            aByte = ESC;
                        }
                        escape = false;
                    }
                    os.write(aByte);
                }
            }

            return os.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    default ByteBuf[] delimitersHdlc() {
        return new ByteBuf[] {
                Unpooled.wrappedBuffer(new byte[] {FLAG})
        };
    }
}
