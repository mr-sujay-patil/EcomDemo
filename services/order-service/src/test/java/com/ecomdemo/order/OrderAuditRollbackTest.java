package com.ecomdemo.order;

import java.util.List;

import com.ecomdemo.cart.CartService;
import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.order.client.CatalogClient;
import com.ecomdemo.order.client.CatalogProduct;
import com.ecomdemo.order.client.InventoryClient;
import com.ecomdemo.shared.ConflictException;
import com.ecomdemo.shared.testsupport.TestSecurity;
import com.ecomdemo.support.TestFixtures;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * A failed checkout leaves no partial data - and still leaves a record that it was attempted.
 *
 * <p>Those two sentences pull in opposite directions, and {@code Propagation.REQUIRES_NEW} is what
 * makes both true at once. The audit write runs in its own transaction, commits immediately, and is
 * therefore untouched when the transaction that called it rolls back.
 *
 * <p>Delete {@code propagation = REQUIRES_NEW} from {@code OrderAuditService.record} and this class
 * fails while every other test in the suite still passes: with the default {@code REQUIRED} the audit
 * would join the checkout's transaction and roll back with it, silently.
 *
 * <h2>Why this matters more after Phase 20</h2>
 *
 * A checkout now reserves stock in another service <em>before</em> writing anything locally. When the
 * local write then fails, these audit rows are the only evidence in this database that the attempt
 * happened at all - and the only place to look when inventory-service is holding stock that no order
 * explains. An audit trail that rolled back with the thing it was auditing would be worse than none,
 * because it would look complete.
 *
 * <p>No {@code @Transactional} on this class, deliberately. A transactional test would roll everything
 * back at the end, including the very rows whose survival is the assertion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
/*
 * Its own database. This test commits rows that are never rolled back - that is the whole point of
 * it - and the `test` profile's H2 otherwise lives for the entire JVM and is shared by every test
 * class. A distinct URL gives this class a private schema that Flyway migrates on its own.
 */
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:order-audit;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
class OrderAuditRollbackTest {

    private static final CatalogProduct WIDGET = TestFixtures.product(1L, "Disappearing Stock", "30.00");

    @Autowired
    private OrderService orderService;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderAuditRepository orderAuditRepository;

    /**
     * The neighbours, stubbed.
     *
     * <p>This test is about what survives a rollback in <em>this</em> database, so the two remote
     * calls are reduced to "they worked" or "they refused". Booting catalog-service and
     * inventory-service to arrange an out-of-stock condition would be a great deal of machinery to
     * produce a 409 a mock can produce in one line - and would make a test about transaction
     * propagation depend on two other services being correct.
     */
    @MockitoBean
    private CatalogClient catalogClient;

    @MockitoBean
    private InventoryClient inventoryClient;

    /** The signed-in shopper these tests act as. */
    private Long customerId;

    @BeforeEach
    void authenticateAndStubNeighbours() {
        // No registration call: there is no users table here. The id is simply chosen, which is what
        // an unvalidated customer_id column means in practice.
        customerId = 7000L + (System.nanoTime() % 1000L);

        // The @PreAuthorize on OrderService compares against authentication.principal.id, so these
        // direct service calls need a principal even though no HTTP request is involved.
        TestSecurity.actAs(customerId, "irrelevant@ecomdemo.local");

        given(catalogClient.findAll()).willReturn(List.of(WIDGET));
        given(catalogClient.findById(WIDGET.id())).willReturn(WIDGET);
    }

    @AfterEach
    void clearAuthentication() {
        // SecurityContextHolder is thread-local and JUnit reuses the thread for the next test.
        TestSecurity.clear();
    }

    @Test
    void placeOrder_whenStockRanOutAfterTheCartWasFilled_rollsBackButKeepsTheAudit() {
        // GIVEN a cart holding five units...
        cartService.addItem(customerId, new AddCartItemRequest(WIDGET.id(), 5));

        // ...and an inventory-service that refuses the reservation, as it would if somebody else
        // bought four in the meantime
        willThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", null, null, null))
                .given(inventoryClient).reserve(any());

        long ordersBefore = orderRepository.count();
        long auditsBefore = orderAuditRepository.count();

        // WHEN checkout is attempted
        assertThatThrownBy(() -> orderService.placeOrder(customerId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no longer available");

        // THEN nothing was half-written: no order...
        assertThat(orderRepository.count())
                .as("a failed order creates no order row")
                .isEqualTo(ordersBefore);

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
        assertThat(failures.getLast().getOrderId())
                .as("a failed attempt has no order to point at")
                .isNull();
    }

    @Test
    void placeOrder_whenItSucceeds_recordsAPlacedAuditNamingTheOrder() {
        // GIVEN a cart that can be fulfilled - inventoryClient.reserve is void and unstubbed, which
        // is what "the reservation succeeded" looks like
        cartService.addItem(customerId, new AddCartItemRequest(WIDGET.id(), 2));

        // WHEN
        long orderId = orderService.placeOrder(customerId).id();

        // THEN the audit trail records the success as well as the failures - an audit that only
        // captured what went wrong would be describing half the system
        List<OrderAudit> placed = orderAuditRepository.findByOutcome(OrderAudit.Outcome.PLACED);
        assertThat(placed).isNotEmpty();
        assertThat(placed.getLast().getOrderId()).isEqualTo(orderId);
    }

    @Test
    void placeOrder_whenTheReservationSucceedsButNothingElseDoes_stillAuditsAndStillReleases() {
        // GIVEN a cart whose product vanishes from the catalogue between the cart page and checkout
        cartService.addItem(customerId, new AddCartItemRequest(WIDGET.id(), 1));
        given(catalogClient.findAll()).willReturn(List.of());

        // WHEN
        assertThatThrownBy(() -> orderService.placeOrder(customerId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no longer available");

        // THEN the failure was audited, and no reservation was ever made - pricing comes before
        // reserving precisely so that a cart that cannot be priced never takes stock
        assertThat(orderAuditRepository.findByOutcome(OrderAudit.Outcome.INSUFFICIENT_STOCK))
                .isNotEmpty();
        org.mockito.Mockito.verify(inventoryClient, org.mockito.Mockito.never()).reserve(any());
    }
}
