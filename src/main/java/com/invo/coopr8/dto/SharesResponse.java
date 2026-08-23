package com.invo.coopr8.dto;

import com.invo.coopr8.model.Shares;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SharesResponse {

    private String responseCode;
    private String responseMessage;
    private Shares shares;

}
