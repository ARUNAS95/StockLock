package com.stocklock.order;

import com.stocklock.order.dto.CreateOrderRequest;
import com.stocklock.order.dto.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @Operation(summary = "Create an order from ACTIVE reservations of one user")
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse order = orderService.create(request);
        return ResponseEntity.created(URI.create("/api/orders/" + order.orderId())).body(order);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get an order")
    public OrderResponse get(@PathVariable UUID orderId) {
        return orderService.get(orderId);
    }

    @PostMapping("/{orderId}/confirm")
    @Operation(summary = "Confirm the order: consume the reserved stock")
    public OrderResponse confirm(@PathVariable UUID orderId) {
        return orderService.confirm(orderId);
    }

    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel a CREATED order and release its reserved stock")
    public OrderResponse cancel(@PathVariable UUID orderId) {
        return orderService.cancel(orderId);
    }
}
