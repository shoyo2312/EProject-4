package com.tiktok.crypto.hash;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashUtilsTest {

    @Test
    void bcrypt_matchesOnlyTheOriginal() {
        String hash = HashUtils.bcryptHash("hunter2");

        assertThat(hash).isNotEqualTo("hunter2");
        assertThat(HashUtils.bcryptMatches("hunter2", hash)).isTrue();
        assertThat(HashUtils.bcryptMatches("hunter3", hash)).isFalse();
    }

    @Test
    void sha256_isTheStandardHexDigest() {
        assertThat(HashUtils.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
