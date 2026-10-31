package com.acme.cloud.orders.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class OrderResourceTest {

    @Test
    void createsReadsAndCancelsOrder() {
        String orderId = given()
                .contentType("application/json")
                .body("""
                        {
                          "customerId": "customer-test-1",
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
    void rejectsInvalidOrder() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "customerId": "customer-test-2",
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
    void returnsNotFoundForUnknownOrder() {
        given()
                .when()
                .get("/api/v1/orders/00000000-0000-0000-0000-000000000001")
                .then()
                .statusCode(404)
                .body("code", equalTo("ORDER_NOT_FOUND"));
    }

    @Test
    void listsOrdersWithPagination() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "customerId": "customer-test-list",
                          "currency": "USD",
                          "items": [
                            {"productId": "product-list", "quantity": 1, "unitPrice": 2.50}
                          ]
                        }
                        """)
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201);

        given()
                .queryParam("page", 0)
                .queryParam("size", 1)
                .when()
                .get("/api/v1/orders")
                .then()
                .statusCode(200)
                .body("size()", equalTo(1));
    }
}
