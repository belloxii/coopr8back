package com.invo.coopr8.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class LoanDto {

    private BigDecimal amount;
    private String type;
    private String purpose;
    private String remark;
    private Integer duration;
    private String accountDetails;
    private String guarantor1;
    private String guarantor2;

}
