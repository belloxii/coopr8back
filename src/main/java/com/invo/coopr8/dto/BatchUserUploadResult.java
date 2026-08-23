package com.invo.coopr8.dto;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BatchUserUploadResult {
    private final int totalRows;
    private final int createdCount;
    private final int skippedCount;
    private final int rejectedCount;
    private final List<BatchUserUploadRowResult> results;
}
