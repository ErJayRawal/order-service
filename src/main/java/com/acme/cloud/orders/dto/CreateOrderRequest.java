package com.acme.cloud.orders.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateOrderRequest(
        @NotBlank @Size(max = 128) String customerId,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid CreateOrderItemRequest> items) {
}
