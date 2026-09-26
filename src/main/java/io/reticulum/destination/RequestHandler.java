package io.reticulum.destination;

import lombok.Value;

import java.util.List;

@Value
public class RequestHandler {
    String path;
    ResponseGenerator responseGenerator;
    RequestPolicy allow;
    List<byte[]> allowedList;
    /**
     * Whether resource responses from this handler are auto-compressed before
     * sending. Mirrors the {@code auto_compress} argument of
     * {@code Destination.register_request_handler}.
     */
    boolean autoCompress;
}
