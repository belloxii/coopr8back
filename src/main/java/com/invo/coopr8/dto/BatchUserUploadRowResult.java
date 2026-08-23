package com.invo.coopr8.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BatchUserUploadRowResult {
    private final int rowNumber;
    private final String email;
    private final boolean created;
    private final boolean skipped;
    private final String responseCode;
    private final String message;
    private final String ledgerId;
}
