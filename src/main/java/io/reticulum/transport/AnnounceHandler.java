package io.reticulum.transport;

import io.reticulum.identity.Identity;

public interface AnnounceHandler {
    String getAspectFilter();

    void receivedAnnounce(byte[] destinationHash, Identity announcedIdentity, byte[] appData);
}
