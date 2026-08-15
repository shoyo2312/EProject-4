package com.tiktok.authservice.service;

import com.tiktok.authservice.repository.UserRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsernameGeneratorTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UsernameGenerator generator = new UsernameGenerator(userRepository);

    @Test
    void usesTheLocalPartWhenItIsFree() {
        free();

        assertThat(generator.generate("Nguyen.Van.A@gmail.com")).isEqualTo("nguyen.van.a");
    }

    /** No address at all — a Facebook account registered with a phone number. */
    @Test
    void fallsBackWhenThereIsNothingToDeriveFrom() {
        free();

        assertThat(generator.generate(null)).isEqualTo("user");
    }

    /** An address whose local part survives sanitising as fewer than three characters. */
    @Test
    void fallsBackWhenTheLocalPartSanitisesToAlmostNothing() {
        free();

        assertThat(generator.generate("陳@example.com")).isEqualTo("user");
    }

    @Test
    void appendsDigitsWhenTheNameIsTaken() {
        free();
        when(userRepository.existsByUsernameIgnoreCaseAndDeletedAtIsNull("taken")).thenReturn(true);

        String generated = generator.generate("taken@example.com");

        assertThat(generated).isNotEqualTo("taken").startsWith("taken");
    }

    private void free() {
        when(userRepository.existsByUsernameIgnoreCaseAndDeletedAtIsNull(anyString())).thenReturn(false);
    }
}
