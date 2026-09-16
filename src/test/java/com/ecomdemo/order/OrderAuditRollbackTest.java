package com.ecomdemo.order;

import java.math.BigDecimal;
import java.util.List;

import com.ecomdemo.cart.CartService;
import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.product.ProductService;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.customer.CustomerService;
import com.ecomdemo.customer.dto.RegisterRequest;
import com.ecomdemo.product.dto.ProductResponse;
import com.ecomdemo.support.TestSecurity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A failed checkout leaves no partial data - and still leaves a record that it was attempted.
 *
 * <p>Those two sentences pull in opposite directions, and {@code Propagation.REQUIRES_NEW} is what
 * makes both true at once. The audit write runs in its own transaction, commits immediately, and is
 * therefore untouched when the transaction that called it rolls back.
 *
 * <p>Delete {@code propagation = REQUIRES_NEW} from {@code OrderAuditService.record} and this class
 * fails while every other test in the suite still passes: with the default {@code REQUIRED} the audit
 * would join the checkout's transaction and roll back with it, silently. That is the difference the
 * test exists to pin down.
 *
 * <p>No {@code @Transactional} on this class, deliberately. A transactional test would roll
 * everything back at the end, including the very rows whose survival is the assertion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
/**
 * Its own database. This test commits rows that are never rolled back - that is the whole point of
 * it - and the {@code test} profile's H2 otherwise lives for the entire JVM and is shared by every
 * test class. Committed orders would then leak into {@code OrderRepositoryTest}, whose assertions
 * are about an empty table, and the suite would pass or fail depending on the order JUnit happened
 * to pick. A distinct URL gives this class a private schema that Flyway migrates on its own.
 */
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:ecomdemo-audit;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
class OrderAuditRollbackTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private CustomerService customerService;

    /** The signed-in shopper these tests act as, registered once per test. */
    private Long customerId;

    @BeforeEach
    void registerAndAuthenticate() {
        customerId = customerService.register(new RegisterRequest(
                "audit-" + System.nanoTime() + "@ecomdemo.local", "password123", "Test Shopper")).id();
        // The @PreAuthorize on OrderService compares against authentication.principal.id, so these
        // direct service calls need a principal even though no HTTP request is involved.
        TestSecurity.actAs(customerId, "irrelevant@ecomdemo.local");
    }

    @AfterEach
    void clearAuthentication() {
        // SecurityContextHolder is thread-local and JUnit reuses the thread for the next test.
        TestSecurity.clear();
    }


    @Autowired
    private ProductService productService;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderAuditRepository orderAuditRepository;

    @Test
    void placeOrder_whenStockRanOutAfterTheCartWasFilled_rollsBackButKeepsTheAudit() {
        // GIVEN a cart holding five units...
        ProductResponse product = productService.create(new ProductRequest(
                "Disappearing Stock", "Sells out mid-checkout", new BigDecimal("30.00"), 5, null));
        cartService.addItem(customerId, new AddCartItemRequest(product.id(), 5));

        // ...and stock that drops to one before checkout, as it would if somebody else bought four
        productService.update(product.id(), new ProductRequest(
                "Disappearing Stock", "Sells out mid-checkout", new BigDecimal("30.00"), 1, null));

        long ordersBefore = orderRepository.count();
        long auditsBefore = orderAuditRepository.count();

        // WHEN checkout is attempted
        assertThatThrownBy(() -> orderService.placeOrder(customerId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only 1 unit(s)");

        // THEN nothing was half-written: no order...
        assertThat(orderRepository.count())
                .as("a failed order creates no order row")
                .isEqualTo(ordersBefore);

        // ...no stock movement...
        assertThat(productService.findById(product.id()).stockQuantity())
                .as("a failed order moves no stock")
                .isEqualTo(1);

        // ...and the cart is untouched, still holding what the customer put in it
        assertThat(cartService.getCart(customerId).items())
                .as("a failed order does not consume the cart")
                .hasSize(1);

        // AND YET the attempt was recorded, because that write was in a transaction of its own
        assertThat(orderAuditRepository.count())
                .as("the audit row survived the rollback")
                .isEqualTo(auditsBefore + 1);

        List<OrderAudit> failures = orderAuditRepository.findByOutcome(
                OrderAudit.Outcome.INSUFFICIENT_STOCK);
        assertThat(failures).isNotEmpty();
        assertThat(failures.getLast().getDetail()).contains("Disappearing Stock");
        assertThat(failures.getLast().getOrderId())
                .as("a failed attempt has no order to point at")
                .isNull();
    }

    @Test
    void placeOrder_whenItSucceeds_recordsAPlacedAuditNamingTheOrder() {
        // GIVEN a cart that can be fulfilled
        ProductResponse product = productService.create(new ProductRequest(
                "Plentiful Stock", "Always available", new BigDecimal("12.00"), 50, null));
        cartService.addItem(customerId, new AddCartItemRequest(product.id(), 2));

        // WHEN
        long orderId = orderService.placeOrder(customerId).id();

        // THEN the audit trail records the success as well as the failures - an audit that only
        // captured what went wrong would be describing half the system
        List<OrderAudit> placed = orderAuditRepository.findByOutcome(OrderAudit.Outcome.PLACED);
        assertThat(placed).isNotEmpty();
        assertThat(placed.getLast().getOrderId()).isEqualTo(orderId);
    }
}
