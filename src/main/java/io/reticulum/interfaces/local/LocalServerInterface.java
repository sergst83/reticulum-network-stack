package io.reticulum.interfaces.local;

import io.reticulum.Transport;
import io.reticulum.interfaces.AbstractConnectionInterface;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static io.reticulum.interfaces.InterfaceMode.MODE_FULL;

@Slf4j
public class LocalServerInterface extends AbstractConnectionInterface {

    private final int bindPort;
    private final ServerSocket server;
    @Getter
    private final AtomicInteger clients = new AtomicInteger(0);

    public LocalServerInterface(Transport transport, int port) throws IOException {
        this.transport = transport;
        this.bindPort = port;
        this.IN = true;
        this.OUT = false;
        this.name = "Reticulum";
        this.interfaceMode = MODE_FULL;
        this.server = new ServerSocket(bindPort);
        this.server.setReuseAddress(true);
        this.bitrate = 1000_000_000;
        this.online.set(true);

        waitConnection();
    }

    private void waitConnection() {
        while (!server.isClosed()) {
            try (var socket = server.accept()) {
                incomingConnection(socket);
            } catch (IOException e) {
                log.error("Error while accept socket", e);
            }
        }
    }

    private void incomingConnection(Socket socket) {
        var spawnedInterface = new LocalClientInterface(transport, name, socket);
        spawnedInterface.setEnabled(true);
        spawnedInterface.setIN(this.IN);
        spawnedInterface.setOUT(this.OUT);
        spawnedInterface.setParentInterface(this);
        spawnedInterface.setBitrate(this.bitrate);
        log.trace("Accepting new connection to shared instance: {}", spawnedInterface.getInterfaceName());
        transport.getInterfaces().add(spawnedInterface);
        transport.getLocalClientInterfaces().add(spawnedInterface);
        clients.getAndIncrement();
        var tread = Executors.defaultThreadFactory().newThread(spawnedInterface);
        tread.start();
    }

    @Override
    public void processIncoming(byte[] data) {

    }

    @Override
    public void processOutgoing(byte[] data) {
        //pass
    }
}
