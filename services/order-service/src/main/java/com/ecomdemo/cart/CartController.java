package com.ecomdemo.cart;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.shared.security.SecurityUser;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cart API. Every mutation returns the whole updated cart, so a client never has to re-fetch it
 * to redraw the page.
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public CartResponse view(@AuthenticationPrincipal SecurityUser principal) {
        return cartService.getCart(principal.getId());
    }

    @PostMapping("/items")
    public CartResponse addItem(@AuthenticationPrincipal SecurityUser principal,
                                @Valid @RequestBody AddCartItemRequest request) {
        return cartService.addItem(principal.getId(), request);
    }

    @PutMapping("/items/{productId}")
    public CartResponse updateItem(@AuthenticationPrincipal SecurityUser principal,
                                   @PathVariable Long productId,
                                   @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.updateItemQuantity(principal.getId(), productId, request);
    }

    @DeleteMapping("/items/{productId}")
    public CartResponse removeItem(@AuthenticationPrincipal SecurityUser principal,
                                   @PathVariable Long productId) {
        return cartService.removeItem(principal.getId(), productId);
    }
}
