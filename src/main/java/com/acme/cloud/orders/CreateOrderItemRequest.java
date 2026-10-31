package com.acme.cloud.orders;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateOrderItemRequest(
        @NotBlank @Size(max = 100) String productId,
        @Positive @Max(10000) int quantity,
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal unitPrice) {
}
