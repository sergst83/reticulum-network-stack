package io.reticulum.cryptography;

/**
 * Thrown when a {@link Token} cannot be authenticated or decrypted.
 * <p>
 * Mirrors the {@code ValueError} raised by {@code Token.decrypt} and
 * {@code Token.verify_hmac} in {@code RNS/Cryptography/Token.py}. Callers that
 * perform trial decryption (ratchets) treat this as "wrong key, try the next
 * one" rather than a fatal condition.
 */
public class TokenValidationException extends RuntimeException {

    public TokenValidationException(final String message) {
        super(message);
    }

    public TokenValidationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
