package io.reticulum.interfaces.local;

import io.reticulum.Transport;
import io.reticulum.interfaces.AbstractConnectionInterface;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

import static io.reticulum.interfaces.InterfaceMode.MODE_FULL;

@Slf4j
public class LocalServerInterface extends AbstractConnectionInterface {

    private final ServerSocket server;
    @Getter
    private final AtomicInteger clients = new AtomicInteger(0);

    public LocalServerInterface(Transport transport, int port) throws IOException {
        this.transport = transport;
        this.IN = true;
        this.OUT = false;
        this.interfaceName = "Reticulum";
        this.interfaceMode = MODE_FULL;
        this.server = new ServerSocket(port);
        this.server.setReuseAddress(true);
        this.bitrate = 1_000_000_000;
        this.online.set(true);
    }

    @Override
    public void run() {
        while (!server.isClosed()) {
            try (var socket = server.accept()) {
                incomingConnection(socket);
            } catch (IOException e) {
                log.error("Error while accept socket", e);
            }
        }
    }

    private void incomingConnection(Socket socket) {
        var spawnedInterface = new LocalClientInterface(transport, interfaceName, socket);
        spawnedInterface.setIN(IN);
        spawnedInterface.setOUT(OUT);
        spawnedInterface.setParentInterface(this);
        spawnedInterface.setBitrate(bitrate);
        log.trace("Accepting new connection to shared instance: {}", spawnedInterface.getInterfaceName());
        transport.getInterfaces().add(spawnedInterface);
        transport.getLocalClientInterfaces().add(spawnedInterface);
        clients.incrementAndGet();
        spawnedInterface.start();
    }

    @Override
    public void processIncoming(byte[] data) {

    }

    @Override
    public void processOutgoing(byte[] data) {
        //pass
    }
}
