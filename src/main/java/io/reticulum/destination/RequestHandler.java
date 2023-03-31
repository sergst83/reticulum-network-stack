package io.reticulum.destination;

import lombok.Value;

import java.util.List;
import java.util.function.Function;

@Value
public class RequestHandler {
    String path;
    Function<Request, byte[]> responseGenerator;
    RequestPolicy allow;
    List<byte[]> allowedList;
}
