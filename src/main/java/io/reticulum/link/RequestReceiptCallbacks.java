package io.reticulum.link;

import lombok.Data;

import java.util.function.Consumer;

@Data
public class RequestReceiptCallbacks {
    Consumer<RequestReceipt> response;
    Consumer<RequestReceipt> failed;
    Consumer<RequestReceipt> progress;
}
