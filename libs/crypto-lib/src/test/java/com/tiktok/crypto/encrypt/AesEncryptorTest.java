package com.tiktok.crypto.encrypt;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesEncryptorTest {

    private final AesEncryptor encryptor = new AesEncryptor(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void roundTrips() {
        assertThat(encryptor.decrypt(encryptor.encrypt("alice@example.com"))).isEqualTo("alice@example.com");
    }

    /** A fresh IV per call: equal plaintexts must not produce equal ciphertexts. */
    @Test
    void theSamePlaintext_encryptsDifferentlyEachTime() {
        assertThat(encryptor.encrypt("x")).isNotEqualTo(encryptor.encrypt("x"));
    }

    /** GCM authenticates: a flipped bit is an error, never a quietly different plaintext. */
    @Test
    void aTamperedCiphertext_isRefused() {
        byte[] bytes = Base64.getDecoder().decode(encryptor.encrypt("alice@example.com"));
        bytes[bytes.length - 1] ^= 1;

        assertThatThrownBy(() -> encryptor.decrypt(Base64.getEncoder().encodeToString(bytes)))
                .isInstanceOf(IllegalStateException.class);
    }
}
