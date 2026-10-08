package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Same rule as the two signups — see {@link UsernameRule}. */
public record ChangeUsernameRequest(
        @NotBlank
        @Pattern(regexp = UsernameRule.REGEXP, message = UsernameRule.MESSAGE)
        String username
) {
}
