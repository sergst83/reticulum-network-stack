package io.reticulum.link;

import lombok.Data;

@Data
public class RequestReceiptCallbacks {
    Object response;
    Object failed;
    Object progress;
}
