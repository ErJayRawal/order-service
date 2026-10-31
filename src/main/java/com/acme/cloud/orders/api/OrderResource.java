package com.acme.cloud.orders.api;

import com.acme.cloud.orders.dto.CreateOrderRequest;
import com.acme.cloud.orders.dto.OrderResponse;
import com.acme.cloud.orders.service.OrderService;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import io.quarkus.security.Authenticated;

@Path("/api/v1/orders")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Orders")
@Authenticated
public class OrderResource {

    private static final int MAX_CUSTOMER_ID_LENGTH = 128;
    private final OrderService orderService;
    private final SecurityIdentity identity;

    @Inject
    public OrderResource(OrderService orderService, SecurityIdentity identity) {
        this.orderService = orderService;
        this.identity = identity;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create an order")
    public Response create(@Valid CreateOrderRequest request, @Context UriInfo uriInfo) {
        OrderResponse order = orderService.create(request, customerId());
        URI location = uriInfo.getAbsolutePathBuilder().path(order.id().toString()).build();
        return Response.created(location).entity(order).build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get an order")
    public OrderResponse get(@PathParam("id") UUID id) {
        return orderService.get(id, customerId());
    }

    @GET
    @Operation(summary = "List orders")
    public List<OrderResponse> list(
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size) {
        return orderService.list(customerId(), page, size);
    }

    @POST
    @Path("/{id}/cancel")
    @Operation(summary = "Cancel a pending order")
    public OrderResponse cancel(@PathParam("id") UUID id) {
        return orderService.cancel(id, customerId());
    }

    private String customerId() {
        String subject = identity.getPrincipal().getName();
        if (subject == null || subject.isBlank() || subject.length() > MAX_CUSTOMER_ID_LENGTH) {
            throw new ForbiddenException("The authenticated identity has an invalid subject");
        }
        return subject;
    }
}
