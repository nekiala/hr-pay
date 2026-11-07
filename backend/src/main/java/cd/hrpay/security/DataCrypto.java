package cd.hrpay.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Encrypts sensitive identifiers (bank and mobile money accounts, CNSS and tax numbers, authenticator secrets) before
 * they reach the database, with AES-256-GCM and a key kept outside it: HRPAY_DATA_KEY (base64 of 32 bytes) or the
 * file named by HRPAY_DATA_KEY_FILE. Encrypted values read "enc:v1:..."; a value without that prefix, written before
 * a key was set, is read as it is and encrypted on its next save. Without a key nothing is encrypted, and the System
 * page says so.
 */
@Service
public class DataCrypto {

    private static final Logger log = LoggerFactory.getLogger(DataCrypto.class);
    static final String PREFIX = "enc:v1:";
    private static final int IV = 12;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public DataCrypto(@Value("${app.data-key:}") String key, @Value("${app.data-key-file:}") String keyFile) {
        this.key = load(key, keyFile);
        if (this.key == null) {
            log.warn("No data key (HRPAY_DATA_KEY or HRPAY_DATA_KEY_FILE): bank accounts and ID numbers are stored unencrypted.");
        }
    }

    public boolean enabled() {
        return key != null;
    }

    public String encrypt(String plain) {
        if (plain == null || key == null || plain.startsWith(PREFIX)) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] sealed = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV + sealed.length];
            System.arraycopy(iv, 0, out, 0, IV);
            System.arraycopy(sealed, 0, out, IV, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException("This value is encrypted but no data key is set (HRPAY_DATA_KEY).");
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, in, 0, IV));
            return new String(c.doFinal(in, IV, in.length - IV), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt a stored value: is HRPAY_DATA_KEY the key it was written with?", e);
        }
    }

    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    private static SecretKeySpec load(String key, String keyFile) {
        String text = key == null ? "" : key.trim();
        if (text.isEmpty() && keyFile != null && !keyFile.isBlank()) {
            try {
                text = Files.readString(Path.of(keyFile.trim())).trim();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot read the data key file " + keyFile, e);
            }
        }
        if (text.isEmpty()) {
            return null;
        }
        byte[] bytes = Base64.getDecoder().decode(text);
        if (bytes.length != 32) {
            throw new IllegalStateException("The data key must be 32 bytes in base64: openssl rand -base64 32");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
