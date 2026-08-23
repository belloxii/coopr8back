package com.invo.coopr8.utils;

import java.security.SecureRandom;
import java.util.Base64;

public class KeyGen {
    public static void main(String[] args) {
        byte[] key = new byte[32]; // 256-bit
        new SecureRandom().nextBytes(key);
        String base64Key = Base64.getEncoder().encodeToString(key);
        // System.out.println("Generated Key: " + base64Key);
    }
}
