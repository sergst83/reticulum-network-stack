package io.reticulum.interfaces.autointerface;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.net.InetAddress;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class Peer {
    private final InetAddress address;
    private final int port;
}
