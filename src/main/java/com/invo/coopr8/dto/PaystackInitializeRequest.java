package com.invo.coopr8.dto;

import lombok.Data;

import java.util.Map;

@Data
public class PaystackInitializeRequest {
    private String email;
    private int amount;
    private String callback_url;
    private Map<String, Object> metadata;
}
