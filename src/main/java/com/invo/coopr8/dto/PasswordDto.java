package com.invo.coopr8.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PasswordDto {

    @NotBlank(message = "oldPassword is required")
    private String oldPassword;

    @NotBlank(message = "newPassword is required")
    private String newPassword;

    @NotBlank(message = "confirmPassword is required")
    private String confirmPassword;

    @NotBlank(message = "otp is required")
    private String otp;

}
