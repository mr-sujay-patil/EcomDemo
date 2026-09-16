package com.ecomdemo.product;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.ecomdemo.shared.NotFoundException;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;
import com.ecomdemo.support.TestFixtures;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ProductService}.
 *
 * <p>{@code MockitoExtension} starts no Spring context at all — the service is a plain object with a
 * mock passed to its constructor, which is exactly why constructor injection was worth the
 * boilerplate. These tests run in milliseconds and fail for one reason only: a business rule broke.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    @Nested
    class FindAll {

        @Test
        void findAll_withProductsInTheCatalogue_returnsThemAsResponses() {
            // GIVEN
            given(productRepository.findAll()).willReturn(List.of(
                    TestFixtures.product(1L, "Keyboard", "129.99"),
                    TestFixtures.product(2L, "Mouse", "49.50")));

            // WHEN
            List<ProductResponse> found = productService.findAll();

            // THEN
            assertThat(found).extracting(ProductResponse::id, ProductResponse::name)
                    .containsExactly(tuple(1L, "Keyboard"), tuple(2L, "Mouse"));
        }

        @Test
        void findAll_withAnEmptyCatalogue_returnsEmptyList() {
            // GIVEN
            given(productRepository.findAll()).willReturn(List.of());

            // WHEN / THEN - an empty catalogue is not an error
            assertThat(productService.findAll()).isEmpty();
        }
    }

    @Nested
    class FindById {

        @Test
        void findById_whenTheProductExists_returnsIt() {
            // GIVEN
            given(productRepository.findById(1L))
                    .willReturn(Optional.of(TestFixtures.product(1L, "Keyboard", "129.99")));

            // WHEN
            ProductResponse found = productService.findById(1L);

            // THEN
            assertThat(found.id()).isEqualTo(1L);
            assertThat(found.name()).isEqualTo("Keyboard");
            assertThat(found.price()).isEqualByComparingTo("129.99");
        }

        @Test
        void findById_whenTheProductIsMissing_throwsNotFound() {
            // GIVEN
            given(productRepository.findById(9999L)).willReturn(Optional.empty());

            // WHEN / THEN - the service throws a domain exception, never returns null
            assertThatThrownBy(() -> productService.findById(9999L))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Product 9999 not found");
        }
    }

    @Nested
    class Create {

        @Test
        void create_withAValidRequest_savesAndReturnsTheProduct() {
            // GIVEN - save() echoes back whatever it was handed, as Hibernate would
            given(productRepository.save(any(Product.class)))
                    .willAnswer(invocation -> TestFixtures.withId(invocation.getArgument(0), 7L));

            // WHEN
            ProductResponse created = productService.create(
                    new ProductRequest("Webcam", "1080p", new BigDecimal("79.99"), "Peripherals"));

            // THEN
            assertThat(created.id()).isEqualTo(7L);
            assertThat(created.name()).isEqualTo("Webcam");
        }

        @Test
        void create_withMoreThanTwoDecimalPlaces_roundsThePriceToTwo() {
            // GIVEN
            given(productRepository.save(any(Product.class)))
                    .willAnswer(invocation -> TestFixtures.withId(invocation.getArgument(0), 1L));

            // WHEN - 9.999 cannot be a real price
            ProductResponse created = productService.create(
                    new ProductRequest("Sticker", null, new BigDecimal("9.999"), null));

            // THEN - normalised on the way in, so the database never sees the extra digit
            assertThat(created.price()).isEqualByComparingTo("10.00");
            assertThat(created.price().scale()).isEqualTo(2);
        }
    }

    @Nested
    class Update {

        @Test
        void update_whenTheProductExists_mutatesItWithoutCallingSave() {
            // GIVEN
            Product existing = TestFixtures.product(1L, "Keyboard", "129.99");
            given(productRepository.findById(1L)).willReturn(Optional.of(existing));

            // WHEN
            ProductResponse updated = productService.update(1L,
                    new ProductRequest("Keyboard Pro", "Now with knobs", new BigDecimal("149.00"), "Peripherals"));

            // THEN - the entity itself changed
            assertThat(updated.name()).isEqualTo("Keyboard Pro");
            assertThat(updated.price()).isEqualByComparingTo("149.00");
            assertThat(existing.getName()).isEqualTo("Keyboard Pro");

            // AND no save() was needed: the entity is managed, so Hibernate's dirty checking
            // flushes the change at commit. Asserting the absence of the call is the only way to
            // pin that behaviour down.
            verify(productRepository, never()).save(any());
        }

        @Test
        void update_whenTheProductIsMissing_throwsNotFound() {
            // GIVEN
            given(productRepository.findById(9999L)).willReturn(Optional.empty());

            // WHEN / THEN
            ProductRequest request = new ProductRequest("Ghost", null, new BigDecimal("1.00"), null);
            assertThatThrownBy(() -> productService.update(9999L, request))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Product 9999 not found");
            verify(productRepository, never()).save(any());
        }
    }

    @Nested
    class Delete {

        @Test
        void delete_whenTheProductExists_deletesIt() {
            // GIVEN
            Product existing = TestFixtures.product(1L);
            given(productRepository.findById(1L)).willReturn(Optional.of(existing));

            // WHEN
            productService.delete(1L);

            // THEN - deleted by entity, not by id, because the lookup already proved it exists
            verify(productRepository).delete(existing);
        }

        @Test
        void delete_whenTheProductIsMissing_throwsNotFoundAndDeletesNothing() {
            // GIVEN
            given(productRepository.findById(9999L)).willReturn(Optional.empty());

            // WHEN / THEN
            assertThatThrownBy(() -> productService.delete(9999L))
                    .isInstanceOf(NotFoundException.class);
            verify(productRepository, never()).delete(any());
        }
    }

    /*
     * A RequireEntity group lived here until Phase 20, covering the one method that deliberately
     * returned an entity rather than a DTO so that the cart and checkout could make stock decisions
     * on a live, managed Product.
     *
     * The method is gone and could not survive the split: what made it useful was that the caller
     * shared this service's transaction, and a caller in another process shares nothing. order-service
     * reads prices over HTTP and gets an immutable snapshot, which is all a boundary can hand over.
     *
     * Deleting the tests with the method is the right move. Keeping them green against some
     * replacement would have meant inventing a reason for the replacement to exist.
     */
}
