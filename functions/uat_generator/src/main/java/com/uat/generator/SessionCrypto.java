package com.uat.generator;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Seals small payloads (the CRM session) with AES-256-GCM so they can live in a
 * cookie: the browser can't read or modify them, and any tampering fails to open.
 *
 * Key: env var SESSION_SECRET (any long random string; it is hashed to 256 bits).
 * Generate one with:  openssl rand -base64 48
 * Rotating SESSION_SECRET signs every user out.
 *
 * Pure Java, no dependencies.
 */
public final class SessionCrypto {

    public static final String PREFIX = "s1.";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AAD = "tp_crm_sid".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SessionCrypto(String secret) {
        if (secret == null || secret.trim().length() < 16) {
            throw new IllegalArgumentException("SESSION_SECRET must be at least 16 characters.");
        }
        try {
            byte[] k = MessageDigest.getInstance("SHA-256")
                    .digest(secret.trim().getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(k, "AES");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The configured instance, or null when SESSION_SECRET is not set or too short. */
    public static SessionCrypto fromEnv() {
        String s = System.getenv("SESSION_SECRET");
        if (s == null || s.trim().length() < 16) return null;
        return new SessionCrypto(s);
    }

    public String seal(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(AAD);
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Could not seal session", e);
        }
    }

    /** The original text, or null if the value is malformed, tampered with, or sealed with another key. */
    public String open(String sealed) {
        if (sealed == null || !sealed.startsWith(PREFIX)) return null;
        try {
            byte[] in = Base64.getUrlDecoder().decode(sealed.substring(PREFIX.length()));
            if (in.length <= IV_BYTES) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            c.updateAAD(AAD);
            byte[] pt = c.doFinal(in, IV_BYTES, in.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
