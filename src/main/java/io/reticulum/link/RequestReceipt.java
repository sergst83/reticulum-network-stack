package io.reticulum.link;

import io.reticulum.packet.PacketReceipt;
import lombok.Data;

import java.util.function.Consumer;

@Data
public class RequestReceipt {
    private Link link;
    private byte[] requestId;
    private int responseSize;
    private int responseTransferSize;

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

    public void responseReceived(byte[] responseData) {

    }

    public void requestTimedOut(Object timeout) {

    }
}
