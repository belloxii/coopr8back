package com.invo.coopr8.dto.platform;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformLoginResponse {

    private String jwt;
    private Long adminId;
    private String email;
    private String role;
}

