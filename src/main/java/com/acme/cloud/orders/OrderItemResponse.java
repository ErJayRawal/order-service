package com.acme.cloud.orders;

import java.math.BigDecimal;

public record OrderItemResponse(String productId, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
}
