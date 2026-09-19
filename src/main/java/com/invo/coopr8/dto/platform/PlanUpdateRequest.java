package com.invo.coopr8.dto.platform;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanUpdateRequest {

    @NotBlank(message = "Plan name is required")
    private String name;

    @NotNull(message = "Plan price is required")
    @DecimalMin(value = "0.00", message = "Price cannot be negative")
    private BigDecimal price;

    private BigDecimal perUserPrice;
    private Integer includedUsers;

    private Boolean aiScanningEnabled;
    private Boolean ecommerceEnabled;
    private Boolean active;
    private Integer sortOrder;
}

