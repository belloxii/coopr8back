package com.invo.coopr8.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

public class TxnIdGen {

    public static String generateTransactionId() {
        // Generate a timestamp in "yyyyMMddHHmm" format (12 digits: Year, Month, Day, Hour, Minute)
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));

        // Generate a 3-digit random number (from 000 to 999)
        String randomDigits = String.format("%03d", new Random().nextInt(1000));

        return timestamp + randomDigits; // 12 (timestamp) + 3 (random) = 15 digits
    }
}
