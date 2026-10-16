package com.tiktok.moderation.text;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class MaxCodePointsValidator implements ConstraintValidator<MaxCodePoints, CharSequence> {

    private int max;

    @Override
    public void initialize(MaxCodePoints constraint) {
        this.max = constraint.value();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null || Character.codePointCount(value, 0, value.length()) <= max;
    }
}
