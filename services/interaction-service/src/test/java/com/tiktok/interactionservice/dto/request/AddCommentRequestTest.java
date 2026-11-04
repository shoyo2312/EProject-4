package com.tiktok.interactionservice.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The comment is sanitized before it is validated, so the limit counts what is stored, the way a
 * reader counts it — and a comment of only invisible characters is blank.
 */
class AddCommentRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void invisibleCharactersAreDropped_andDecomposedVietnameseIsComposed() {
        AddCommentRequest request = new AddCommentRequest(" hay​ quá ‮ ", null);

        assertThat(request.content()).isEqualTo("hay quá");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void aCommentOfOnlyInvisibleCharacters_isBlank() {
        assertThat(validator.validate(new AddCommentRequest("​‍ㅤ⠀", null))).isNotEmpty();
    }

    @Test
    void aStickerOnItsOwn_needsNoText() {
        AddCommentRequest request = new AddCommentRequest(null, null, "grinning-face");

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.text()).isEmpty();
    }

    @Test
    void neitherTextNorSticker_isInvalid() {
        assertThat(validator.validate(new AddCommentRequest("  ", null, " "))).isNotEmpty();
        assertThat(validator.validate(new AddCommentRequest(null, null, null))).isNotEmpty();
    }

    @Test
    void anEditIsSanitizedAndMustNotBeBlank() {
        assertThat(new EditCommentRequest(" hay​ quá ").content()).isEqualTo("hay quá");
        assertThat(validator.validate(new EditCommentRequest("​‍"))).isNotEmpty();
    }

    /** 150 emoji is 300 UTF-16 units; the reader sees 150 characters, and so does the limit. */
    @Test
    void theLimitCountsCodePoints() {
        assertThat(validator.validate(new AddCommentRequest("😀".repeat(150), null))).isEmpty();
        assertThat(validator.validate(new AddCommentRequest("😀".repeat(151), null))).isNotEmpty();
    }
}
