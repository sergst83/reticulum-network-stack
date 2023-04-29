package io.reticulum.destination;

public abstract class AbstractDestination {

    public abstract DestinationType getType();

    public abstract byte[] getHash();


    public abstract byte[] encrypt(byte[] data);
}
