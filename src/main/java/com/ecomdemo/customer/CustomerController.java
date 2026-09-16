package com.ecomdemo.customer;

import com.ecomdemo.customer.dto.CustomerResponse;
import com.ecomdemo.customer.dto.RegisterRequest;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration and the caller's own profile.
 *
 * <p>{@code @AuthenticationPrincipal} is how the authenticated user reaches a controller. The
 * alternative - reading {@code SecurityContextHolder} inside the service - would let any service
 * silently depend on there being a logged-in user, and would make it untestable without a security
 * context. Taking the id as a method argument keeps the service layer free of security just as it is
 * free of HTTP.
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    /** Public: you cannot be required to authenticate in order to get an account. */
    @PostMapping("/register")
    public ResponseEntity<CustomerResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customerService.register(request));
    }

    /** Whoever you are, authenticated. The id comes from the principal, never from the URL. */
    @GetMapping("/me")
    public CustomerResponse me(@AuthenticationPrincipal SecurityUser principal) {
        return customerService.findById(principal.getId());
    }
}
