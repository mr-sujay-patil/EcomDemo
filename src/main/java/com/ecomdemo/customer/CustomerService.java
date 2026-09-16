package com.ecomdemo.customer;

import java.time.Clock;
import java.util.Locale;

import com.ecomdemo.common.ConflictException;
import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.customer.dto.CustomerResponse;
import com.ecomdemo.customer.dto.RegisterRequest;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and profile lookup.
 *
 * <p>The only place in the application that turns a password into a hash, which is why
 * {@link PasswordEncoder} is injected rather than a {@code BCryptPasswordEncoder} constructed here:
 * the algorithm is chosen once, in {@code SecurityConfiguration}, and changing it later is a one-line
 * change rather than a search.
 */
@Service
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public CustomerService(CustomerRepository customerRepository,
                           PasswordEncoder passwordEncoder,
                           Clock clock) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * Registers a shopper. Always a CUSTOMER - the request record has no role field, so there is no
     * payload a client could send that would make them an administrator.
     */
    @Transactional
    public CustomerResponse register(RegisterRequest request) {
        String email = normaliseEmail(request.email());
        if (customerRepository.existsByEmail(email)) {
            // A friendly 409 for the common case. The unique constraint in V6 is what actually
            // guarantees it: two simultaneous registrations would both pass this check.
            throw new ConflictException("An account with that email already exists");
        }

        Customer customer = new Customer(
                email,
                passwordEncoder.encode(request.password()),
                request.displayName(),
                Customer.Role.CUSTOMER,
                clock.instant());

        return CustomerResponse.from(customerRepository.save(customer));
    }

    public CustomerResponse findById(Long id) {
        return CustomerResponse.from(requireCustomer(id));
    }

    /** Used by the cart and order features to attach their rows to a real user. */
    public Customer requireEntity(Long id) {
        return requireCustomer(id);
    }

    private Customer requireCustomer(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Customer", id));
    }

    /**
     * Email addresses are compared case-insensitively by every mail system and case-sensitively by
     * PostgreSQL's unique index. Lower-casing on the way in is what stops {@code Sam@x.com} and
     * {@code sam@x.com} becoming two accounts - and it has to be applied on login too, which is why
     * this is shared with {@link CustomerDetailsService}.
     */
    static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
