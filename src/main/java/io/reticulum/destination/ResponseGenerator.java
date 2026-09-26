package io.reticulum.destination;

/**
 * Produces a {@link Response} for an incoming {@link Request}.
 * <p>
 * A dedicated interface rather than {@code Function<Request, Response>} so that
 * it does not collide with the byte-array form of
 * {@link Destination#registerRequestHandler} during overload resolution.
 */
@FunctionalInterface
public interface ResponseGenerator {

    /**
     * @param request the incoming request
     * @return the response, or null to send nothing
     */
    Response generate(Request request);
}
