package io.reticulum.cryptography;

import com.macasaet.fernet.Key;
import com.macasaet.fernet.TokenValidationException;
import org.apache.commons.codec.digest.HmacUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;

import static io.reticulum.utils.IdentityUtils.concatArrays;
import static javax.crypto.Cipher.DECRYPT_MODE;
import static javax.crypto.Cipher.ENCRYPT_MODE;
import static org.apache.commons.codec.digest.HmacAlgorithms.HMAC_SHA_256;

public class Fernet extends Key {

    private static final int IV_SIZE = 16;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public Fernet(byte[] concatenatedKeys) {
        super(concatenatedKeys);
    }

    public byte[] encrypt(byte[] plainText) throws IOException {
        var secureRandom = new SecureRandom();
        final byte[] retval = new byte[IV_SIZE];
        secureRandom.nextBytes(retval);

        var iv = new IvParameterSpec(retval);

        var ciphertext = encrypt(plainText, iv);
        var signedParts = concatArrays(iv.getIV(), ciphertext);

        return concatArrays(signedParts, new HmacUtils(HMAC_SHA_256, getSigningKey()).hmac(signedParts));
    }

    public byte[] decrypt(byte[] token) {
        var iv = Arrays.copyOfRange(token, 0, IV_SIZE);
        var cipherText = Arrays.copyOfRange(token, IV_SIZE, token.length - 32);

        return decrypt(cipherText, new IvParameterSpec(iv));
    }

    public static byte[] generateFernetKey() {
        try (var baos = new ByteArrayOutputStream()) {
            generateKey().writeTo(baos);

            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] encrypt(final byte[] payload, final IvParameterSpec initializationVector) {
        final SecretKeySpec encryptionKeySpec = getEncryptionKeySpec();
        try {
            final Cipher cipher = Cipher.getInstance(getCipherTransformation(), BouncyCastleProvider.PROVIDER_NAME);
            cipher.init(ENCRYPT_MODE, encryptionKeySpec, initializationVector);
            return cipher.doFinal(payload);
        } catch (final NoSuchAlgorithmException | NoSuchPaddingException | NoSuchProviderException e) {
            throw new IllegalStateException("Unable to access cipher " + getCipherTransformation() + ": " + e.getMessage(), e);
        } catch (final InvalidKeyException | InvalidAlgorithmParameterException e) {
            throw new IllegalStateException(
                    "Unable to initialise encryption cipher with algorithm " + encryptionKeySpec.getAlgorithm()
                            + " and format " + encryptionKeySpec.getFormat() + ": " + e.getMessage(),
                    e);
        } catch (final IllegalBlockSizeException | BadPaddingException e) {
            throw new IllegalStateException("Unable to encrypt data: " + e.getMessage(), e);
        }
    }

    protected byte[] decrypt(final byte[] cipherText, final IvParameterSpec initializationVector) {
        try {
            final Cipher cipher = Cipher.getInstance(getCipherTransformation(), BouncyCastleProvider.PROVIDER_NAME);
            cipher.init(DECRYPT_MODE, getEncryptionKeySpec(), initializationVector);
            return cipher.doFinal(cipherText);
        } catch (final NoSuchAlgorithmException | NoSuchPaddingException
                       | InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException
                       | NoSuchProviderException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } catch (final BadPaddingException bpe) {
            throw new TokenValidationException("Invalid padding in token: " + bpe.getMessage(), bpe);
        }
    }

    protected String getCipherTransformation() {
        return getEncryptionAlgorithm() + "/CBC/PKCS7Padding";
    }
}
