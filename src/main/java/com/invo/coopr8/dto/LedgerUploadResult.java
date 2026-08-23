package com.invo.coopr8.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LedgerUploadResult {
    private final int totalRows;
    private final int matchedCount;
    private final int rejectedCount;
    private final int savingCount;
    private final int repayCount;
    private final BigDecimal totalSaved;
    private final BigDecimal totalRepaid;
    private final List<LedgerRowResult> results;
}
