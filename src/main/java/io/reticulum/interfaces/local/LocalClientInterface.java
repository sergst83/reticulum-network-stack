package io.reticulum.interfaces.local;

import io.reticulum.Transport;
import io.reticulum.interfaces.AbstractConnectionInterface;
import io.reticulum.interfaces.HDLC;
import io.reticulum.interfaces.InterfaceMode;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static io.reticulum.utils.CommonUtils.exit;
import static io.reticulum.utils.CommonUtils.panic;
import static io.reticulum.utils.IdentityUtils.concatArrays;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

@Setter
@Slf4j
public class LocalClientInterface extends AbstractConnectionInterface implements HDLC {

    private static final long RECONNECT_WAIT = TimeUnit.SECONDS.toMillis(3);

    private static final int HW_MTU = 1064;

    private Socket socket;
    private SocketAddress targetAddress;
    private LocalServerInterface parentInterface;
    private volatile boolean isConnectedToSharedInstance;
    private volatile boolean neverConnected;
    private volatile boolean detached;
    private volatile boolean reconnecting;
    private volatile boolean writing;
    private volatile boolean receives;
    private volatile boolean forceBitrate;

    private LocalClientInterface() {
        enabled = true;
        online.set(false);

        IN = true;
        OUT = false;
        reconnecting = false;
        neverConnected = true;
        detached = false;
        interfaceMode = InterfaceMode.MODE_FULL;

        bitrate = 1000_000_000;
        writing = false;

        forceBitrate = false;
    }

    public LocalClientInterface(String name, Socket socket) {
        this();
        this.receives = true;
        this.interfaceName = name;
        this.socket = socket;
        this.targetAddress = socket.getRemoteSocketAddress();
        this.isConnectedToSharedInstance = true;
        this.online.set(true);
        this.neverConnected = false;
    }

    public LocalClientInterface(String name, int port) throws IOException {
        this();
        this.interfaceName = name;
        this.socket = new Socket();
        this.targetAddress = new InetSocketAddress(port);
        connect();
    }

    public void readLoop() {
        try {
            var inputStream = socket.getInputStream();
            var inFrame = false;
            var escape = false;
            var dataBuffer = new ByteArrayOutputStream();
            if (inputStream.available() > 0) {
                while (inputStream.available() > 0) {
                    var singlByte = inputStream.read();
                    if (inFrame && singlByte == FLAG) {
                        inFrame = false;
                        processIncoming(dataBuffer.toByteArray());
                    } else if (singlByte == FLAG) {
                        inFrame = true;
                        dataBuffer.reset();
                    } else if (inFrame && dataBuffer.size() < HW_MTU) {
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
                            dataBuffer.write(singlByte);;
                        }
                    }
                }
            } else {
                online.set(false);
                if (isConnectedToSharedInstance && !detached) {
                    log.warn("Socket for {} was closed, attempting to reconnect...", this);
                    Transport.getInstance().sharedConnectionDisappeared();
                    reconnect();
                } else {
                    teardown(true);
                }
            }
        } catch (IOException | InterruptedException e) {
            online.set(false);
            log.error("An interface error occurred. Tearing down {}", this, e);
            teardown(false);
        }
    }

    private synchronized void teardown(boolean noWarning) {
        online.set(false);
        OUT = false;
        IN = false;

        Transport.getInstance().getInterfaces().remove(this);
        if (Transport.getInstance().getLocalClientInterfaces().remove(this)) {
            if (nonNull(parentInterface)) {
                parentInterface.getClients().getAndDecrement();
                Transport.getInstance().getOwner().persistData();
            }
        }

        if (isFalse(noWarning)) {
            log.error("The interface {} experienced an unrecoverable error and is being torn down. Restart Reticulum to attempt to open this interface again.", this);
            if (Transport.getInstance().getOwner().isPanicOnIntefaceError()) {
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

    private synchronized void reconnect() throws IOException, InterruptedException {
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
                        log.debug("Connection attempt {} for {} failed.", attempts, this, e);
                    }
                }

                if (isFalse(neverConnected)) {
                    log.info("Reconnected socket for {}.", this);
                }

                reconnecting = false;
                Transport.getInstance().sharedConnectionDisappeared();
            }
        } else {
            log.error("Attempt to reconnect on a non-initiator shared local interface. This should not happen.");
            throw new IOException("Attempt to reconnect on a non-initiator local interface");
        }
    }

    private synchronized boolean connect() throws IOException {
        socket.connect(targetAddress);
        isConnectedToSharedInstance = true;
        neverConnected = false;
        online.set(true);

        return true;
    }

    @Override
    public synchronized void processIncoming(byte[] data) {
        if (forceBitrate) {
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(data.length / bitrate * 8L));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        rxb.updateAndGet(previous -> previous.add(BigInteger.valueOf(data.length)));
        if (nonNull(parentInterface)) {
            parentInterface.getRxb().updateAndGet(previous -> previous.add(BigInteger.valueOf(data.length)));
        }

        Transport.getInstance().inbound(data, this);
    }

    public synchronized void processOutgoing(final byte[] data) {
        if (online.get()) {
            try {
                var outputStream = new DataOutputStream(socket.getOutputStream());
                if (!socket.isConnected()) {
                    reconnect();
                }
                writing = true;
                var toWrite = concatArrays(new byte[]{FLAG}, escapeHdlc(data), new byte[]{FLAG});
                outputStream.write(toWrite);
                outputStream.flush();
                writing = false;
                txb.updateAndGet(previous -> previous.add(BigInteger.valueOf(toWrite.length)));
                if (nonNull(parentInterface)) {
                    parentInterface.getTxb().updateAndGet(previous -> previous.add(BigInteger.valueOf(toWrite.length)));
                }
            } catch (IOException | InterruptedException e) {
                log.error("Exception occurred while transmitting via {}, tearing down interface", this, e);
                teardown(false);
            }
        }
    }

    @Override
    public void run() {
        while (!this.socket.isClosed()) {
            readLoop();
        }
    }

    @Override
    public void launch() {
        start();
    }

    @Override
    public synchronized void detach() {
        if (nonNull(socket)) {
            try {
                log.debug("Detaching {}", this);
                socket.close();
                socket = null;
                detached = true;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void sentAnnounce(boolean fromSpawned) {
        if (fromSpawned) {
            oaFreqDeque.add(0, Instant.now());
        }
    }

    @Override
    public void receivedAnnounce(boolean fromSpawned) {
        if (fromSpawned) {
            iaFreqDeque.add(0, Instant.now());
        }
    }
}
