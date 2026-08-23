package com.invo.coopr8.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TransferRequest {

    private String sourceledgerID;
    private String destinationledgerID;
    private BigDecimal amount;
    
}
