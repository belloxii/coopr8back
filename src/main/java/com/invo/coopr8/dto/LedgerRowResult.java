package com.invo.coopr8.dto;

import java.math.BigDecimal;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LedgerRowResult {
    private final int rowNumber;
    private final String psn;
    private final String memberName;
    private final String ledgerId;
    private final boolean matched;
    private final BigDecimal savingPosted;
    private final BigDecimal repayPosted;
    private final String message;
}
