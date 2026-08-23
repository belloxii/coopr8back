package com.invo.coopr8.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DebitRequest {

    private String ledgerID;
    private BigDecimal amount;
}
