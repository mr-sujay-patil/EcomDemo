package com.ecomdemo.order;

import java.net.URI;
import java.util.List;

import com.ecomdemo.shared.security.SecurityUser;
import com.ecomdemo.order.dto.OrderResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The order API. POST takes no body: the cart already holds everything the server needs, and
 * accepting line items here would let a client order things it never put in the cart.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> place(@AuthenticationPrincipal SecurityUser principal) {
        OrderResponse order = orderService.placeOrder(principal.getId());
        return ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(order);
    }

    @GetMapping
    public List<OrderResponse> list(@AuthenticationPrincipal SecurityUser principal) {
        return orderService.findAll(principal.getId());
    }

    @GetMapping("/{id}")
    public OrderResponse get(@AuthenticationPrincipal SecurityUser principal, @PathVariable Long id) {
        return orderService.findById(id, principal.getId());
    }
}
