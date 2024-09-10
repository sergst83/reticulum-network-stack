package io.reticulum.message;

import io.reticulum.channel.Channel;

public abstract class MessageBase {

    /**
     *     Defines a unique identifier for a message class. <br/>
     *
     *     * Must be unique within all classes registered with a {@link Channel} <br/>
     *     * Must be less than <strong>0xf000</strong>. Values greater than or equal to <strong>0xf000</strong> are reserved. <br/>
     */
    public abstract Integer msgType();

    /**
     * Create and return the binary representation of the message
     *
     * @return binary representation of message
     */
    public abstract byte[] pack();

    /**
     * Populate message from binary representation
     *
     * @param raw binary representation
     */
    public abstract void unpack(byte[] raw);
}
