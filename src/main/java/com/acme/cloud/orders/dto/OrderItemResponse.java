package com.acme.cloud.orders.dto;

import java.math.BigDecimal;

public record OrderItemResponse(String productId, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
}
