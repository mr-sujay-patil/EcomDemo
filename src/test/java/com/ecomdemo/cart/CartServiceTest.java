package com.ecomdemo.cart;

import java.util.Optional;

import com.ecomdemo.cart.dto.AddCartItemRequest;
import com.ecomdemo.cart.dto.CartResponse;
import com.ecomdemo.cart.dto.UpdateCartItemRequest;
import com.ecomdemo.common.ConflictException;
import com.ecomdemo.customer.CustomerService;
import com.ecomdemo.common.NotFoundException;
import com.ecomdemo.product.Product;
import com.ecomdemo.product.ProductService;
import com.ecomdemo.support.TestFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link CartService}.
 *
 * <p>Two collaborators are mocked for different reasons. {@code CartRepository} is mocked to keep
 * the database out; {@code ProductService} is mocked because this test is about cart rules, not
 * about whether product lookup works — that is {@code ProductServiceTest}'s job. Mocking at the
 * service boundary rather than reaching for the product repository keeps the two suites independent.
 */
@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private ProductService productService;

    @Mock
    private CustomerService customerService;

    @InjectMocks
    private CartService cartService;

    /** The signed-in shopper these tests act as. */
    private static final Long CUSTOMER_ID = 42L;

    private Cart cart;
    private Product keyboard;

    @BeforeEach
    void setUp() {
        cart = new Cart(TestFixtures.customer(CUSTOMER_ID));
        keyboard = TestFixtures.product(1L, "Mechanical Keyboard", "129.99", 40);
    }

    private void cartExists() {
        given(cartRepository.findByCustomerIdWithItems(CUSTOMER_ID)).willReturn(Optional.of(cart));
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
            given(customerService.requireEntity(CUSTOMER_ID))
                    .willReturn(TestFixtures.customer(CUSTOMER_ID));
            given(cartRepository.save(any(Cart.class))).willAnswer(i -> i.getArgument(0));

            // THEN the cart is created on first use, owned by them and empty
            Cart created = cartService.requireCart(CUSTOMER_ID);
            assertThat(created.getCustomer().getId()).isEqualTo(CUSTOMER_ID);
            assertThat(created.isEmpty()).isTrue();
        }
    }

    @Nested
    class GetCart {

        @Test
        void getCart_whenTheCartHasItems_returnsAServerCalculatedTotal() {
            // GIVEN - 2 x 129.99 + 1 x 49.50
            cartExists();
            cart.addOrIncrease(keyboard, 2);
            cart.addOrIncrease(TestFixtures.product(2L, "Wireless Mouse", "49.50", 120), 1);

            // WHEN
            CartResponse response = cartService.getCart(CUSTOMER_ID);

            // THEN
            assertThat(response.items()).hasSize(2);
            assertThat(response.totalItems()).isEqualTo(3);
            assertThat(response.total()).isEqualByComparingTo("309.48");
        }

        @Test
        void getCart_whenTheCartIsEmpty_returnsZeroTotal() {
            // GIVEN
            cartExists();

            // WHEN / THEN - an empty cart is a valid state, not an error
            CartResponse response = cartService.getCart(CUSTOMER_ID);
            assertThat(response.items()).isEmpty();
            assertThat(response.total()).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    class AddItem {

        @Test
        void addItem_whenTheProductIsNotYetInTheCart_addsANewLine() {
            // GIVEN
            cartExists();
            given(productService.requireEntity(1L)).willReturn(keyboard);

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
            given(productService.requireEntity(1L)).willReturn(keyboard);
            cart.addOrIncrease(keyboard, 1);

            // WHEN
            CartResponse response = cartService.addItem(CUSTOMER_ID, new AddCartItemRequest(1L, 2));

            // THEN - one line of 3, not two lines
            assertThat(response.items()).hasSize(1);
            assertThat(response.items().getFirst().quantity()).isEqualTo(3);
        }

        @Test
        void addItem_whenTheProductDoesNotExist_throwsNotFound() {
            // GIVEN
            cartExists();
            given(productService.requireEntity(9999L))
                    .willThrow(new NotFoundException("Product 9999 not found"));

            // WHEN / THEN
            AddCartItemRequest request = new AddCartItemRequest(9999L, 1);
            assertThatThrownBy(() -> cartService.addItem(CUSTOMER_ID, request))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Product 9999 not found");
        }

        @Test
        void addItem_whenTheQuantityExceedsStock_throwsConflictAndLeavesTheCartUntouched() {
            // GIVEN - only 40 in stock
            cartExists();
            given(productService.requireEntity(1L)).willReturn(keyboard);

            // WHEN / THEN
            AddCartItemRequest request = new AddCartItemRequest(1L, 41);
            assertThatThrownBy(() -> cartService.addItem(CUSTOMER_ID, request))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Only 40 unit(s)")
                    .hasMessageContaining("requested 41");
            assertThat(cart.isEmpty()).isTrue();
        }

        @Test
        void addItem_whenTheCartAlreadyHoldsMostOfTheStock_countsWhatIsAlreadyThere() {
            // GIVEN - 39 of 40 already in the cart
            cartExists();
            given(productService.requireEntity(1L)).willReturn(keyboard);
            cart.addOrIncrease(keyboard, 39);

            // WHEN / THEN - adding 2 more asks for 41 in total, which is one too many.
            // Checking the request in isolation would have let this through.
            AddCartItemRequest request = new AddCartItemRequest(1L, 2);
            assertThatThrownBy(() -> cartService.addItem(CUSTOMER_ID, request))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("requested 41");
            assertThat(cart.getItems().getFirst().getQuantity()).isEqualTo(39);
        }
    }

    @Nested
    class UpdateItemQuantity {

        @Test
        void updateItemQuantity_whenTheLineExists_setsTheNewQuantity() {
            // GIVEN
            cartExists();
            cart.addOrIncrease(keyboard, 2);

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
        void updateItemQuantity_whenTheNewQuantityExceedsStock_throwsConflict() {
            // GIVEN
            cartExists();
            cart.addOrIncrease(keyboard, 2);

            // WHEN / THEN
            UpdateCartItemRequest request = new UpdateCartItemRequest(999);
            assertThatThrownBy(() -> cartService.updateItemQuantity(CUSTOMER_ID, 1L, request))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Only 40 unit(s)");
            assertThat(cart.getItems().getFirst().getQuantity()).isEqualTo(2);
        }
    }

    @Nested
    class RemoveItem {

        @Test
        void removeItem_whenTheLineExists_removesItAndRecalculatesTheTotal() {
            // GIVEN
            cartExists();
            cart.addOrIncrease(keyboard, 2);
            cart.addOrIncrease(TestFixtures.product(2L, "Wireless Mouse", "49.50", 120), 1);

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
