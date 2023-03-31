package io.reticulum.link;

import io.reticulum.packet.PacketReceipt;

import java.util.function.Consumer;

public class RequestReceipt {
    public RequestReceipt(
            Link link,
            PacketReceipt packetReceipt,
            Consumer<RequestReceipt> responseCallback,
            Consumer<RequestReceipt> failedCallback,
            Consumer<RequestReceipt> progressCallback,
            long localTimeout,
            int length
    ) {

    }

    public RequestReceipt(
            Link link,
            Resource requestResource,
            Consumer<RequestReceipt> responseCallback,
            Consumer<RequestReceipt> failedCallback,
            Consumer<RequestReceipt> progressCallback,
            long localTimeout,
            int length
    ) {

    }
}
