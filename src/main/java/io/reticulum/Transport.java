package io.reticulum;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class Transport implements ExitHandler, PersistData {

    @Getter
    private final Reticulum owner;

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
}
