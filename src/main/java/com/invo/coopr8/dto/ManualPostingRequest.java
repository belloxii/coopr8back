package com.invo.coopr8.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Admin-initiated saving or repayment for a single member. Used when a
 * self-pay member sends a bank-transfer receipt and the admin records it.
 * For repayments, {@link #loanId} may be supplied to target a specific loan;
 * when omitted, the amount is applied across the member's approved loans.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ManualPostingRequest {
    private Long userId;
    private Long loanId;
    private BigDecimal amount;
    private String note;
}
