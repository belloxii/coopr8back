package com.invo.coopr8.dto;

import lombok.Data;

@Data
public class PaystackInitializeResponse {
    private boolean status;
    private String message;
    private PaystackData data;

    @Data
    public static class PaystackData {
        private String authorization_url;
        private String access_code;
        private String reference;
    }
}
