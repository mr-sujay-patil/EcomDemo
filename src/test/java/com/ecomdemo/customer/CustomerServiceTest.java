package com.ecomdemo.customer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.ecomdemo.common.ConflictException;
import com.ecomdemo.customer.dto.CustomerResponse;
import com.ecomdemo.customer.dto.RegisterRequest;
import com.ecomdemo.support.TestFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Registration rules.
 *
 * <p>A <em>real</em> {@link BCryptPasswordEncoder} is used rather than a mock. A mocked encoder would
 * let "the password is hashed" be asserted as "some method was called", which would pass just as
 * happily if the hash were the password itself. With the real one, the test can assert the thing that
 * actually matters: what is stored does not equal what was typed, and yet verifies against it.
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:15:30Z");

    @Mock
    private CustomerRepository customerRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerService(
                customerRepository, passwordEncoder, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ArgumentCaptor<Customer> captureSavedCustomer() {
        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        given(customerRepository.save(captor.capture()))
                .willAnswer(invocation -> TestFixtures.withId(invocation.getArgument(0), 1L));
        return captor;
    }

    @Nested
    class Register {

        @Test
        void register_withANewEmail_storesAHashAndNeverThePassword() {
            // GIVEN
            given(customerRepository.existsByEmail("sam@example.com")).willReturn(false);
            ArgumentCaptor<Customer> saved = captureSavedCustomer();

            // WHEN
            customerService.register(new RegisterRequest("sam@example.com", "correct horse battery", "Sam"));

            // THEN what reaches the database is not the password
            String stored = saved.getValue().getPasswordHash();
            assertThat(stored)
                    .isNotEqualTo("correct horse battery")
                    .startsWith("$2a$");

            // AND it is a hash of that password - one-way, but verifiable
            assertThat(passwordEncoder.matches("correct horse battery", stored)).isTrue();
            assertThat(passwordEncoder.matches("something else", stored)).isFalse();
        }

        @Test
        void register_twiceWithTheSamePassword_producesDifferentHashes() {
            // GIVEN two people who happen to choose the same password
            given(customerRepository.existsByEmail(any())).willReturn(false);
            ArgumentCaptor<Customer> saved = captureSavedCustomer();

            // WHEN
            customerService.register(new RegisterRequest("one@example.com", "same password", "One"));
            String first = saved.getValue().getPasswordHash();
            customerService.register(new RegisterRequest("two@example.com", "same password", "Two"));
            String second = saved.getValue().getPasswordHash();

            // THEN the stored values differ, because BCrypt salts each hash. Without a salt, identical
            // passwords would be identical rows - and one cracked hash would unlock every account
            // that shared it.
            assertThat(first).isNotEqualTo(second);
            assertThat(passwordEncoder.matches("same password", first)).isTrue();
            assertThat(passwordEncoder.matches("same password", second)).isTrue();
        }

        @Test
        void register_always_createsACustomerAndNeverAnAdmin() {
            // GIVEN
            given(customerRepository.existsByEmail(any())).willReturn(false);
            ArgumentCaptor<Customer> saved = captureSavedCustomer();

            // WHEN
            CustomerResponse response = customerService.register(
                    new RegisterRequest("sam@example.com", "password123", "Sam"));

            // THEN there is no payload that could have produced an ADMIN - the request record has no
            // role field at all
            assertThat(saved.getValue().getRole()).isEqualTo(Customer.Role.CUSTOMER);
            assertThat(response.role()).isEqualTo("CUSTOMER");
        }

        @Test
        void register_withAMixedCaseEmail_storesItLowerCased() {
            // GIVEN
            given(customerRepository.existsByEmail("sam@example.com")).willReturn(false);
            ArgumentCaptor<Customer> saved = captureSavedCustomer();

            // WHEN
            customerService.register(new RegisterRequest("  Sam@Example.COM ", "password123", "Sam"));

            // THEN mail is case-insensitive but the unique index is not, so normalising on the way in
            // is what stops one person owning two accounts
            assertThat(saved.getValue().getEmail()).isEqualTo("sam@example.com");
        }

        @Test
        void register_withAnEmailAlreadyTaken_throwsConflictAndSavesNothing() {
            // GIVEN
            given(customerRepository.existsByEmail("taken@example.com")).willReturn(true);

            // WHEN / THEN
            RegisterRequest request = new RegisterRequest("taken@example.com", "password123", "Nope");
            assertThatThrownBy(() -> customerService.register(request))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already exists");

            verify(customerRepository, never()).save(any());
        }
    }

    @Nested
    class FindById {

        @Test
        void findById_whenTheCustomerExists_returnsThemWithoutAnyPasswordField() {
            // GIVEN
            given(customerRepository.findById(1L))
                    .willReturn(Optional.of(TestFixtures.customer(1L, "sam@example.com")));

            // WHEN
            CustomerResponse response = customerService.findById(1L);

            // THEN - the record has no password component at all, which is why this cannot regress
            assertThat(response.email()).isEqualTo("sam@example.com");
            assertThat(CustomerResponse.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .doesNotContain("password", "passwordHash");
        }
    }
}
