package com.tiktok.common.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every service that takes a media URL from a client leans on this class, so the rules are
 * pinned here rather than re-tested per service. No Spring: the validator is a plain object
 * with one dependency.
 */
class MediaUrlValidatorTest {

    private static final MediaUrlProperties PROPERTIES =
            new MediaUrlProperties(List.of("cdn.example.com"), List.of("video-media"));

    @Test
    void httpsUrlOnAllowedHost_isAccepted() {
        assertThat(validate("https://cdn.example.com/a.jpg")).isTrue();
    }

    @Test
    void hostMatchIsCaseInsensitive() {
        assertThat(validate("https://CDN.Example.COM/a.jpg")).isTrue();
    }

    @Test
    void httpsUrlOnForeignHost_isRejected() {
        assertThat(validate("https://evil.example.net/a.jpg")).isFalse();
    }

    /**
     * The allow-list is on the host, not on the string: a foreign host that merely mentions an
     * allowed one — as userinfo, as a path, or as a suffix — must not slip through.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "https://cdn.example.com.evil.net/a.jpg",
            "https://evil.net/cdn.example.com/a.jpg",
            "https://cdn.example.com@evil.net/a.jpg",
            "https://notcdn.example.com/a.jpg"
    })
    void lookalikeHosts_areRejected(String url) {
        assertThat(validate(url)).isFalse();
    }

    /** http is not in the default scheme list, however friendly the host looks. */
    @Test
    void plainHttpOnAllowedHost_isRejected() {
        assertThat(validate("http://cdn.example.com/a.jpg")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD4=",
            "file:///etc/passwd",
            "not a url at all",
            "://missing-scheme"
    })
    void dangerousOrMalformedValues_areRejected(String url) {
        assertThat(validate(url)).isFalse();
    }

    @Test
    void s3UrlIsMatchedAgainstBuckets_notHosts() {
        assertThat(validate("s3://video-media/raw/1.mp4", "https", "s3")).isTrue();
        // The bucket list is separate from the host list, so a CDN hostname is not a valid bucket.
        assertThat(validate("s3://cdn.example.com/raw/1.mp4", "https", "s3")).isFalse();
    }

    @Test
    void s3UrlIsRejectedWhenSchemeNotDeclaredOnTheField() {
        assertThat(validate("s3://video-media/raw/1.mp4")).isFalse();
    }

    /** Optionality is @NotBlank's job; this constraint only judges the value it is given. */
    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankValues_pass(String url) {
        assertThat(validate(url)).isTrue();
    }

    @Test
    void nullValue_passes() {
        assertThat(validate(null)).isTrue();
    }

    /**
     * A service that never configured app.media rejects everything rather than accepting
     * everything — the failure mode has to be the safe one.
     */
    @Test
    void unconfiguredAllowLists_rejectEverything() {
        MediaUrlValidator validator = validatorFor(new MediaUrlProperties(null, null), "https");
        assertThat(validator.isValid("https://cdn.example.com/a.jpg", null)).isFalse();
    }

    private boolean validate(String url, String... schemes) {
        String[] effective = schemes.length == 0 ? new String[]{"https"} : schemes;
        return validatorFor(PROPERTIES, effective).isValid(url, null);
    }

    private MediaUrlValidator validatorFor(MediaUrlProperties properties, String... schemes) {
        MediaUrlValidator validator = new MediaUrlValidator(properties);
        validator.initialize(annotationWithSchemes(schemes));
        return validator;
    }

    private ValidMediaUrl annotationWithSchemes(String... schemes) {
        return new ValidMediaUrl() {
            @Override
            public String[] schemes() {
                return schemes;
            }

            @Override
            public String message() {
                return "";
            }

            @Override
            public Class<?>[] groups() {
                return new Class<?>[0];
            }

            @Override
            public Class<? extends jakarta.validation.Payload>[] payload() {
                return newPayloadArray();
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return ValidMediaUrl.class;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends jakarta.validation.Payload>[] newPayloadArray() {
        return new Class[0];
    }
}
