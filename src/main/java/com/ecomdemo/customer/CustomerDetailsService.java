package com.ecomdemo.customer;

import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the username from an HTTP Basic header into a principal.
 *
 * <p>This is the whole of {@link UserDetailsService}: one method, "given a username, find the user".
 * It deliberately does <em>not</em> check the password. Spring Security's authentication provider
 * calls this to load the stored hash, then asks the {@code PasswordEncoder} whether the submitted
 * password matches it - so the comparison happens in one place, using an algorithm chosen in one
 * place, rather than in whatever code happens to load users.
 *
 * <p>Failure is reported as {@link UsernameNotFoundException}, which the provider turns into the same
 * 401 as a wrong password. That sameness is intentional: distinguishing "no such user" from "wrong
 * password" tells an attacker which email addresses are registered.
 */
@Service
@Transactional(readOnly = true)
public class CustomerDetailsService implements UserDetailsService {

    private final CustomerRepository customerRepository;

    public CustomerDetailsService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override
    public SecurityUser loadUserByUsername(String username) throws UsernameNotFoundException {
        return customerRepository.findByEmail(CustomerService.normaliseEmail(username))
                .map(SecurityUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("No user with that email"));
    }
}
