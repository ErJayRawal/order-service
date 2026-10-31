package com.acme.cloud.orders.api;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class OrderResourceTest {

    @Inject
    DataSource dataSource;

    @Test
    @TestSecurity(user = "customer-test-1")
    void createsReadsAndCancelsOrder() {
        String orderId = given()
                .contentType("application/json")
                .body("""
                        {
                          "currency": "USD",
                          "items": [
                            {"productId": "product-1", "quantity": 2, "unitPrice": 19.99},
                            {"productId": "product-2", "quantity": 1, "unitPrice": 5.00}
                          ]
                        }
                        """)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .header("Location", notNullValue())
                .body("status", equalTo("PENDING"))
                .body("customerId", equalTo("customer-test-1"))
                .body("total", equalTo(44.98f))
                .body("items.size()", equalTo(2))
                .extract()
                .path("id");

        given()
                .when()
                .get("/api/v1/orders/{id}", orderId)
                .then()
                .statusCode(200)
                .body("id", equalTo(orderId))
                .body("items[0].lineTotal", equalTo(39.98f));

        given()
                .when()
                .post("/api/v1/orders/{id}/cancel", orderId)
                .then()
                .statusCode(200)
                .body("status", equalTo("CANCELLED"));

        given()
                .when()
                .post("/api/v1/orders/{id}/cancel", orderId)
                .then()
                .statusCode(200)
                .body("status", equalTo("CANCELLED"));
    }

    @Test
    @TestSecurity(user = "customer-test-2")
    void rejectsInvalidOrder() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "currency": "USD",
                          "items": [
                            {"productId": "product-1", "quantity": 0, "unitPrice": 19.99}
                          ]
                        }
                        """)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"));
    }

    @Test
    @TestSecurity(user = "customer-test-3")
    void acceptsValidCorrelationIdAndReturnsIt() {
        given()
                .header("X-Correlation-ID", "checkout-123.abc_def")
                .when()
                .get("/api/v1/orders/00000000-0000-0000-0000-000000000001")
                .then()
                .statusCode(404)
                .header("X-Correlation-ID", equalTo("checkout-123.abc_def"));
    }

    @Test
    @TestSecurity(user = "customer-test-4")
    void generatesCorrelationIdWhenMissingOrInvalid() {
        given()
                .when()
                .get("/api/v1/orders/00000000-0000-0000-0000-000000000001")
                .then()
                .statusCode(404)
                .header("X-Correlation-ID", matchesPattern("[0-9a-f-]{36}"));

        given()
                .header("X-Correlation-ID", "invalid correlation id")
                .when()
                .get("/api/v1/orders/00000000-0000-0000-0000-000000000001")
                .then()
                .statusCode(404)
                .header("X-Correlation-ID", matchesPattern("[0-9a-f-]{36}"));
    }

    @Test
    @TestSecurity(user = "customer-test-5")
    void returnsNotFoundForUnknownOrder() {
        given()
                .when()
                .get("/api/v1/orders/00000000-0000-0000-0000-000000000001")
                .then()
                .statusCode(404)
                .body("code", equalTo("ORDER_NOT_FOUND"));
    }

    @Test
    @TestSecurity(user = "customer-test-list")
    void listsOrdersWithPagination() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "customerId": "attacker-selected-customer",
                          "currency": "USD",
                          "items": [
                            {"productId": "product-list", "quantity": 1, "unitPrice": 2.50}
                          ]
                        }
                        """)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .body("customerId", equalTo("customer-test-list"));

        given()
                .queryParam("page", 0)
                .queryParam("size", 1)
                .when()
                .get("/api/v1/orders")
                .then()
                .statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].customerId", equalTo("customer-test-list"));
    }

    @Test
    void rejectsUnauthenticatedOrderRequests() {
        given()
                .when()
                .get("/api/v1/orders")
                .then()
                .statusCode(401);
    }

    @Test
    @TestSecurity(user = "customer-test-isolation")
    void cannotReadCancelOrListAnotherCustomersOrder() throws Exception {
        UUID foreignOrderId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO orders (id, customer_id, currency, status, total, created_at, updated_at, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            statement.setObject(1, foreignOrderId);
            statement.setString(2, "another-customer");
            statement.setString(3, "USD");
            statement.setString(4, "PENDING");
            statement.setBigDecimal(5, BigDecimal.ZERO);
            statement.setTimestamp(6, Timestamp.from(createdAt));
            statement.setTimestamp(7, Timestamp.from(createdAt));
            statement.setLong(8, 0);
            statement.executeUpdate();
        }

        given()
                .when()
                .get("/api/v1/orders/{id}", foreignOrderId)
                .then()
                .statusCode(404)
                .body("code", equalTo("ORDER_NOT_FOUND"));

        given()
                .when()
                .post("/api/v1/orders/{id}/cancel", foreignOrderId)
                .then()
                .statusCode(404)
                .body("code", equalTo("ORDER_NOT_FOUND"));

        given()
                .when()
                .get("/api/v1/orders")
                .then()
                .statusCode(200)
                .body("size()", equalTo(0));
    }
}
