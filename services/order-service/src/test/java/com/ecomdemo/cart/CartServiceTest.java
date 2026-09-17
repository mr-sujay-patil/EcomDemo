package com.ecomdemo.cart;

import java.util.List;
import java.util.Optional;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;
import com.ecomdemo.order.client.CatalogClient;
import com.ecomdemo.order.client.CatalogProduct;
import com.ecomdemo.shared.NotFoundException;
import com.ecomdemo.support.TestFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link CartService}.
 *
 * <p>{@code CartRepository} is mocked to keep the database out; {@link CatalogClient} is mocked
 * because this test is about cart rules, not about whether HTTP works.
 *
 * <h2>What these tests stopped covering in Phase 20, and why that is correct</h2>
 *
 * Every stock assertion. This class used to have four tests about refusing to add more than was in
 * stock, including the careful one that counted what was already in the cart. Stock is another
 * service's data now and is checked once, at checkout, against the row itself - so adding to a cart
 * does not consult it, and testing that it does would be testing behaviour that was removed on
 * purpose.
 *
 * <p>Those assertions are not lost; they moved to where the rule now lives, in inventory-service's
 * {@code StockReservationTest} and {@code StockApiIT}. What genuinely changed is the <em>moment</em>
 * a shopper finds out, which is now checkout rather than add-to-cart.
 *
 * <p>Two new tests replace them, covering what the network introduced: a catalogue that is
 * unreachable, and a product that has been deleted out from under a cart.
 */
@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CatalogClient catalogClient;

    @InjectMocks
    private CartService cartService;

    /** The signed-in shopper these tests act as. */
    private static final Long CUSTOMER_ID = 42L;

    private static final CatalogProduct KEYBOARD =
            TestFixtures.product(1L, "Mechanical Keyboard", "129.99");
    private static final CatalogProduct MOUSE =
            TestFixtures.product(2L, "Wireless Mouse", "49.50");

    private Cart cart;

    @BeforeEach
    void setUp() {
        cart = new Cart(CUSTOMER_ID);
    }

    private void cartExists() {
        given(cartRepository.findByCustomerIdWithItems(CUSTOMER_ID)).willReturn(Optional.of(cart));
    }

    /** catalog-service answering normally. */
    private void catalogueIsUp() {
        given(catalogClient.findAll()).willReturn(List.of(KEYBOARD, MOUSE));
    }

    @Nested
    class RequireCart {

        @Test
        void requireCart_whenTheCustomerAlreadyHasACart_returnsItWithoutSaving() {
            // GIVEN
            cartExists();

            // WHEN / THEN
            assertThat(cartService.requireCart(CUSTOMER_ID)).isSameAs(cart);
            verify(cartRepository, never()).save(any());
        }

        @Test
        void requireCart_whenTheCustomerHasNoCartYet_createsOneForThem() {
            // GIVEN a shopper who has never added anything
            given(cartRepository.findByCustomerIdWithItems(CUSTOMER_ID)).willReturn(Optional.empty());
            given(cartRepository.save(any(Cart.class))).willAnswer(i -> i.getArgument(0));

            // THEN the cart is created on first use, owned by them and empty.
            //
            // Note what is NOT consulted: customer-service. Phase 8 looked the Customer up to attach
            // the cart to a real row; there is no such row here and no call to check for one. The id
            // is trusted because it came out of a signature, not out of a request parameter.
            Cart created = cartService.requireCart(CUSTOMER_ID);
            assertThat(created.getCustomerId()).isEqualTo(CUSTOMER_ID);
            assertThat(created.isEmpty()).isTrue();
        }
    }

    @Nested
    class GetCart {

        @Test
        void getCart_whenTheCartHasItems_returnsATotalPricedFromTheCatalogue() {
            // GIVEN - 2 x 129.99 + 1 x 49.50
            cartExists();
            catalogueIsUp();
            cart.addOrIncrease(KEYBOARD.id(), 2);
            cart.addOrIncrease(MOUSE.id(), 1);

            // WHEN
            CartResponse response = cartService.getCart(CUSTOMER_ID);

            // THEN
            assertThat(response.items()).hasSize(2);
            assertThat(response.totalItems()).isEqualTo(3);
            assertThat(response.total()).isEqualByComparingTo("309.48");
        }

        @Test
        void getCart_always_pricesTheWholeCartInOneCall() {
            // GIVEN a cart with two different products
            cartExists();
            catalogueIsUp();
            cart.addOrIncrease(KEYBOARD.id(), 1);
            cart.addOrIncrease(MOUSE.id(), 1);

            // WHEN
            cartService.getCart(CUSTOMER_ID);

            // THEN one call, not one per line. This assertion exists because the N+1 it prevents was
            // nearly free inside one process - a lazy association - and is a round trip per line
            // across a network. The mistake is easy to reintroduce and invisible in any test that
            // only checks the totals.
            verify(catalogClient).findAll();
            verify(catalogClient, never()).findById(any());
        }

        @Test
        void getCart_whenTheCartIsEmpty_returnsZeroTotalWithoutCallingTheCatalogue() {
            // GIVEN
            cartExists();

            // WHEN / THEN - an empty cart is a valid state, not an error, and there is nothing to
            // price, so the network is not touched at all
            CartResponse response = cartService.getCart(CUSTOMER_ID);
            assertThat(response.items()).isEmpty();
            assertThat(response.total()).isEqualByComparingTo("0.00");
            verify(catalogClient, never()).findAll();
        }

        @Test
        void getCart_whenCatalogServiceIsUnreachable_stillRendersTheCart() {
            // GIVEN a catalogue that cannot be reached at all
            cartExists();
            cart.addOrIncrease(KEYBOARD.id(), 2);
            given(catalogClient.findAll())
                    .willThrow(new ResourceAccessException("connection refused"));

            // WHEN
            CartResponse response = cartService.getCart(CUSTOMER_ID);

            // THEN the shopper still sees what they put in the cart, priced at zero and named as
            // unavailable, rather than an error page.
            //
            // This is a judgement call and the opposite one is defensible - wrong totals may be worse
            // than an error. It is chosen because a cart is a read, the numbers are recomputed on the
            // next load, and nothing is bought on the strength of them: checkout prices independently
            // and refuses outright when the catalogue is down.
            assertThat(response.items()).singleElement().satisfies(item -> {
                assertThat(item.productId()).isEqualTo(KEYBOARD.id());
                assertThat(item.quantity()).isEqualTo(2);
                assertThat(item.productName()).isEqualTo("(unavailable)");
                assertThat(item.lineTotal()).isEqualByComparingTo("0.00");
            });
        }

        @Test
        void getCart_whenAProductHasBeenDeletedFromTheCatalogue_keepsTheLineVisible() {
            // GIVEN a cart holding a product the catalogue no longer lists. Nothing prevents this:
            // there is no foreign key from cart_items to products any more.
            cartExists();
            cart.addOrIncrease(KEYBOARD.id(), 1);
            cart.addOrIncrease(999L, 3);
            catalogueIsUp();

            // WHEN
            CartResponse response = cartService.getCart(CUSTOMER_ID);

            // THEN the dead line is shown rather than silently dropped - dropping it would lose the
            // shopper's intent - and the total counts only what could be priced
            assertThat(response.items()).hasSize(2);
            assertThat(response.total()).isEqualByComparingTo("129.99");
        }
    }

    @Nested
    class AddItem {

        @Test
        void addItem_whenTheProductIsNotYetInTheCart_addsANewLine() {
            // GIVEN
            cartExists();
            catalogueIsUp();
            given(catalogClient.findById(1L)).willReturn(KEYBOARD);

            // WHEN
            CartResponse response = cartService.addItem(CUSTOMER_ID, new AddCartItemRequest(1L, 2));

            // THEN
            assertThat(response.items()).singleElement().satisfies(item -> {
                assertThat(item.productId()).isEqualTo(1L);
                assertThat(item.quantity()).isEqualTo(2);
                assertThat(item.unitPrice()).isEqualByComparingTo("129.99");
                assertThat(item.lineTotal()).isEqualByComparingTo("259.98");
            });
        }

        @Test
        void addItem_whenTheProductIsAlreadyInTheCart_increasesTheQuantity() {
            // GIVEN - one already in the cart
            cartExists();
            catalogueIsUp();
            given(catalogClient.findById(1L)).willReturn(KEYBOARD);
            cart.addOrIncrease(KEYBOARD.id(), 1);

            // WHEN
            CartResponse response = cartService.addItem(CUSTOMER_ID, new AddCartItemRequest(1L, 2));

            // THEN - one line of 3, not two lines
            assertThat(response.items()).hasSize(1);
            assertThat(response.items().getFirst().quantity()).isEqualTo(3);
        }

        @Test
        void addItem_whenTheProductDoesNotExist_translatesTheUpstream404IntoNotFound() {
            // GIVEN catalog-service answering 404
            cartExists();
            given(catalogClient.findById(9999L))
                    .willThrow(HttpClientErrorException.create(
                            HttpStatus.NOT_FOUND, "Not Found", null, null, null));

            // WHEN / THEN the shopper sees this service's own NotFoundException - and so the shared
            // {status, message} shape - rather than a leaked upstream body. Translating at the
            // boundary is what stops one service's error format becoming another's API.
            AddCartItemRequest request = new AddCartItemRequest(9999L, 1);
            assertThatThrownBy(() -> cartService.addItem(CUSTOMER_ID, request))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Product 9999 not found");
        }

        @Test
        void addItem_always_checksThatTheProductExists() {
            // GIVEN
            cartExists();
            catalogueIsUp();
            given(catalogClient.findById(1L)).willReturn(KEYBOARD);

            // WHEN
            cartService.addItem(CUSTOMER_ID, new AddCartItemRequest(1L, 1));

            // THEN the one catalogue call on the write path. It is worth its round trip: without it a
            // typo in a product id becomes a cart line that can never be checked out, discovered
            // minutes later at the till.
            verify(catalogClient).findById(1L);
        }
    }

    @Nested
    class UpdateItemQuantity {

        @Test
        void updateItemQuantity_whenTheLineExists_setsTheNewQuantity() {
            // GIVEN
            cartExists();
            catalogueIsUp();
            cart.addOrIncrease(KEYBOARD.id(), 2);

            // WHEN
            CartResponse response = cartService.updateItemQuantity(CUSTOMER_ID, 1L, new UpdateCartItemRequest(5));

            // THEN - set, not incremented
            assertThat(response.items().getFirst().quantity()).isEqualTo(5);
            assertThat(response.total()).isEqualByComparingTo("649.95");
        }

        @Test
        void updateItemQuantity_whenTheProductIsNotInTheCart_throwsNotFound() {
            // GIVEN - the cart is empty
            cartExists();

            // WHEN / THEN
            UpdateCartItemRequest request = new UpdateCartItemRequest(3);
            assertThatThrownBy(() -> cartService.updateItemQuantity(CUSTOMER_ID, 1L, request))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Product 1 is not in the cart");
        }

        @Test
        void updateItemQuantity_toMoreThanExists_isAllowedHereAndRefusedAtCheckout() {
            // GIVEN a cart line
            cartExists();
            catalogueIsUp();
            cart.addOrIncrease(KEYBOARD.id(), 2);

            // WHEN an implausible quantity is set
            CartResponse response = cartService.updateItemQuantity(
                    CUSTOMER_ID, 1L, new UpdateCartItemRequest(999));

            // THEN it is accepted, and this is a deliberate behaviour change rather than a missing
            // check. Phase 8 refused it here, because stock was one field access away. Consulting
            // inventory-service now would be a second network call per cart edit, to answer a
            // question whose answer is stale the moment it is given - the shopper may check out ten
            // minutes later. The reservation at checkout decides against the row itself.
            assertThat(response.items().getFirst().quantity()).isEqualTo(999);
        }
    }

    @Nested
    class RemoveItem {

        @Test
        void removeItem_whenTheLineExists_removesItAndRecalculatesTheTotal() {
            // GIVEN
            cartExists();
            catalogueIsUp();
            cart.addOrIncrease(KEYBOARD.id(), 2);
            cart.addOrIncrease(MOUSE.id(), 1);

            // WHEN
            CartResponse response = cartService.removeItem(CUSTOMER_ID, 1L);

            // THEN
            assertThat(response.items()).singleElement()
                    .satisfies(item -> assertThat(item.productId()).isEqualTo(2L));
            assertThat(response.total()).isEqualByComparingTo("49.50");
        }

        @Test
        void removeItem_whenTheProductIsNotInTheCart_throwsNotFound() {
            // GIVEN
            cartExists();

            // WHEN / THEN
            assertThatThrownBy(() -> cartService.removeItem(CUSTOMER_ID, 42L))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Product 42 is not in the cart");
        }
    }
}
