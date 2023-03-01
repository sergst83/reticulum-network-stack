package io.reticulum;

import io.reticulum.interfaces.ConnectionInterface;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public class Transport implements ExitHandler, PersistData {

    @Getter
    private final Reticulum owner;

    @Getter
    public static final List<ConnectionInterface> interfaces = new ArrayList<>();

    @Override
    public void exitHandler() {
        if (owner.isConnectedToSharedInstance()) {
            persistData();
        }
    }

    @Override
    public void persistData() {
        savePacketHashlist();
        savePathTable();
        saveTunnelTable();
    }

    private void savePacketHashlist() {

    }

    private void savePathTable() {

    }

    private void saveTunnelTable() {

    }

    public void inbound(byte[] data) {

    }
}
