package io.reticulum.interfaces.local;

import io.reticulum.Transport;
import io.reticulum.interfaces.AbstractConnectionInterface;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import static io.reticulum.utils.CommonUtils.exit;
import static io.reticulum.utils.CommonUtils.panic;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

@Setter
@Slf4j
public class LocalClientInterface extends AbstractConnectionInterface {

    private static final long RECONNECT_WAIT = TimeUnit.SECONDS.toMillis(3);
    private static final byte FLAG = Byte.decode("0x7E");
    private static final byte ESC = Byte.decode("0x7D");
    private static final byte ESC_MASK = Byte.decode("0x20");
    private static final int HW_MTU = 1064;

    private static byte[] escape(byte[] data) {
        var result = new byte[0];
        if (nonNull(data) && data.length > 0) {
            var buffer = ByteBuffer.wrap(data);
            for (int i = 0; i < data.length; i++) {
                if (data[i] == ESC) {
                    buffer.position(i);
                    buffer.put(new byte[]{ESC, (byte) (ESC ^ ESC_MASK)});
                }
                if (data[i] == FLAG) {
                    buffer.position(i);
                    buffer.put(new byte[]{ESC, (byte) (FLAG ^ ESC_MASK)});
                }
            }
            result = buffer.array();
        }

        return result;
    }

    private Socket connection;
    private SocketAddress targetAddress;
    private LocalServerInterface parentInterface;
    private boolean isConnectedToSharedInstance;
    private boolean neverConnected = true;
    private boolean detached;
    private boolean reconnecting;
    private boolean writing;

    public LocalClientInterface(Transport owner, String name, Socket socket) {
        this.transport = owner;
        this.name = name;
        this.connection = socket;
        this.targetAddress = socket.getRemoteSocketAddress();
        this.isConnectedToSharedInstance = true;
        this.online.set(true);
        this.neverConnected = false;
    }

    public void readLoop() {
        try {
            var inputStream = new BufferedInputStream(connection.getInputStream());
            var inFrame = false;
            var escape = false;
            var dataBuffer = new byte[0];
            byte[] dataIn = inputStream.readAllBytes();
            if (dataIn.length > 0) {
                var pointer = 0;
                while (pointer < dataIn.length) {
                    var singlByte = dataIn[pointer];
                    pointer++;
                    if (inFrame && singlByte == FLAG) {
                        inFrame = false;
                        processIncoming(dataBuffer);
                    } else if (singlByte == FLAG) {
                        inFrame = true;
                        dataBuffer = new byte[0];
                    } else if (inFrame && dataBuffer.length < HW_MTU) {
                        if (singlByte == ESC) {
                            escape = true;
                        } else {
                            if (escape) {
                                if (singlByte == (FLAG ^ ESC_MASK)) {
                                    singlByte = FLAG;
                                }
                                if (singlByte == (ESC ^ ESC_MASK)) {
                                    singlByte = ESC;
                                }
                                escape = false;
                            }
                            dataBuffer = ArrayUtils.add(dataBuffer, singlByte);
                        }
                    }
                }
            } else {
                online.set(false);
                if (isConnectedToSharedInstance && !detached) {
                    log.warn("Socket for {} was closed, attempting to reconnect...", getInterfaceName());
                    transport.sharedConnectionDisappeared();
                    reconnect();
                } else {
                    this.teardown(true);
                }
            }
        } catch (IOException | InterruptedException e) {
            online.set(false);
            log.error("An interface error occurred. Tearing down {}", this.getInterfaceName(), e);
            this.teardown(false);
        }
    }

    private void teardown(boolean noWarning) {
        online.set(false);
        OUT = false;
        IN = false;

        transport.getInterfaces().remove(this);
        if (transport.getLocalClientInterfaces().remove(this)) {
            if (nonNull(parentInterface)) {
                parentInterface.getClients().getAndDecrement();
                transport.getOwner().persistData();
            }
        }

        if (isFalse(noWarning)) {
            log.error("The interface {} experienced an unrecoverable error and is being torn down. Restart Reticulum to attempt to open this interface again.", this.getInterfaceName());
            if (transport.getOwner().isPanicOnIntefaceError()) {
                panic();
            }
        }

        if (isConnectedToSharedInstance) {
            if (isFalse(noWarning)) {
                log.error("Permanently lost connection to local shared RNS instance. Exiting now.");
            }
            exit();
        }

        interrupt();
    }

    private void reconnect() throws IOException, InterruptedException {
        if (isConnectedToSharedInstance) {
            if (isFalse(reconnecting)) {
                reconnecting = true;
                var attempts = 0;
                while (isFalse(online.get())) {
                    Thread.sleep(RECONNECT_WAIT);
                    attempts++;
                    try {
                        connect();
                    } catch (Exception e) {
                        log.debug("Connection attempt {} for {} failed.", attempts, this.getInterfaceName(), e);
                    }
                }

                if (isFalse(neverConnected)) {
                    log.info("Reconnected socket for {}.", this.getInterfaceName());
                }

                reconnecting = false;
                transport.sharedConnectionDisappeared();
            }
        } else {
            log.error("Attempt to reconnect on a non-initiator shared local interface. This should not happen.");
            throw new IOException("Attempt to reconnect on a non-initiator local interface");
        }
    }

    private void connect() throws IOException {
        connection.connect(targetAddress);
    }

    @Override
    public void processIncoming(byte[] data) {
        rxb.updateAndGet(previous -> previous.add(BigInteger.valueOf(data.length)));
        if (nonNull(parentInterface)) {
            parentInterface.getRxb().updateAndGet(previous -> previous.add(BigInteger.valueOf(data.length)));
        }

        transport.inbound(data);
    }

    public void processOutgoing(final byte[] data) {
        if (online.get()) {
            try {
                var outputStream = new BufferedOutputStream(connection.getOutputStream());
                if (!connection.isConnected()) {
                    reconnect();
                }
                writing = true;
                var toWrite = ArrayUtils.add(ArrayUtils.addAll(new byte[] {FLAG}, escape(data)), FLAG);
                outputStream.write(toWrite);
                outputStream.flush();
                writing = false;
                txb.updateAndGet(previous -> previous.add(BigInteger.valueOf(toWrite.length)));
                if (nonNull(parentInterface)) {
                    parentInterface.getTxb().updateAndGet(previous -> previous.add(BigInteger.valueOf(toWrite.length)));
                }
            } catch (IOException | InterruptedException e) {
                log.error("Exception occurred while transmitting via {}, tearing down interface", this.getInterfaceName(), e);
                teardown(false);
            }
        }
    }

    @Override
    public void run() {
        while (!this.connection.isClosed()) {
            readLoop();
        }
    }
}
