package com.tiktok.authservice.dto.request;

import com.tiktok.authservice.entity.AuthProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CloseAccountRequestTest {

    @Test
    void eachProofOnItsOwn_isAccepted() {
        assertThat(new CloseAccountRequest("secret", null, null, null).isExactlyOneProof()).isTrue();
        assertThat(new CloseAccountRequest(null, "123456", null, null).isExactlyOneProof()).isTrue();
        assertThat(new CloseAccountRequest(null, null, AuthProvider.GOOGLE, "token").isExactlyOneProof()).isTrue();
    }

    @Test
    void noProof_orTwo_orHalfAProviderSignIn_isRefused() {
        assertThat(new CloseAccountRequest(null, null, null, null).isExactlyOneProof()).isFalse();
        assertThat(new CloseAccountRequest("  ", null, null, null).isExactlyOneProof()).isFalse();
        assertThat(new CloseAccountRequest("secret", "123456", null, null).isExactlyOneProof()).isFalse();
        assertThat(new CloseAccountRequest("secret", null, AuthProvider.GOOGLE, "token").isExactlyOneProof()).isFalse();
        assertThat(new CloseAccountRequest(null, null, AuthProvider.GOOGLE, null).isExactlyOneProof()).isFalse();
        assertThat(new CloseAccountRequest(null, null, null, "token").isExactlyOneProof()).isFalse();
    }
}
