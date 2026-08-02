package com.tiktok.authservice.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class OtpGenerator {

    private static final int OTP_LENGTH = 6;
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        int bound = (int) Math.pow(10, OTP_LENGTH);
        int value = random.nextInt(bound);
        return String.format("%0" + OTP_LENGTH + "d", value);
    }
}
