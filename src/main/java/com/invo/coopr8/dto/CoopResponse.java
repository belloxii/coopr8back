package com.invo.coopr8.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CoopResponse {

    private String responseCode;
    private String responseMessage;
    private AccountInfo accountInfo;
    private OTPResponse otpResponse;

}
