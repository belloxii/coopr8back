package com.invo.coopr8.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One line of the salary-deduction schedule sent by the ministry.
 * Members are matched by {@link #psn}. Either amount may be null/zero.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LedgerUploadRow {
    private int rowNumber;
    private String psn;
    private BigDecimal savingAmount;
    private BigDecimal repayAmount;
}
