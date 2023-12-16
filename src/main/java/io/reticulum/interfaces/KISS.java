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

    int CMD_UNKNOWN = 0xFE;
    int CMD_DATA = 0x00;
    int CMD_FREQUENCY   = 0x01;
    int CMD_BANDWIDTH   = 0x02;
    int CMD_TXPOWER     = 0x03;
    int CMD_SF          = 0x04;
    int CMD_CR          = 0x05;
    int CMD_RADIO_STATE = 0x06;
    int CMD_RADIO_LOCK  = 0x07;
    int CMD_DETECT      = 0x08;
    int CMD_LEAVE       = 0x0A;
    int CMD_READY       = 0x0F;
    int CMD_STAT_RX     = 0x21;
    int CMD_STAT_TX     = 0x22;
    int CMD_STAT_RSSI   = 0x23;
    int CMD_STAT_SNR    = 0x24;
    int CMD_BLINK       = 0x30;
    int CMD_RANDOM      = 0x40;
    int CMD_FB_EXT      = 0x41;
    int CMD_FB_READ     = 0x42;
    int CMD_FB_WRITE    = 0x43;
    int CMD_PLATFORM    = 0x48;
    int CMD_MCU         = 0x49;
    int CMD_FW_VERSION  = 0x50;
    int CMD_ROM_READ    = 0x51;
    int CMD_RESET       = 0x55;

    int DETECT_REQ      = 0x73;
    int DETECT_RESP     = 0x46;

    int RADIO_STATE_OFF = 0x00;
    int RADIO_STATE_ON  = 0x01;
    int RADIO_STATE_ASK = 0xFF;

    int CMD_ERROR           = 0x90;
    int ERROR_INITRADIO     = 0x01;
    int ERROR_TXFAILED      = 0x02;
    int ERROR_EEPROM_LOCKED = 0x03;

    int PLATFORM_AVR   = 0x90;
    int PLATFORM_ESP32 = 0x80;

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
