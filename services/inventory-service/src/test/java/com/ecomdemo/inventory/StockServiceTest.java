package com.ecomdemo.inventory;

import java.util.List;
import java.util.Optional;

import com.ecomdemo.inventory.dto.ReservationRequest;
import com.ecomdemo.inventory.dto.StockLevelRequest;
import com.ecomdemo.inventory.dto.StockResponse;
import com.ecomdemo.shared.ConflictException;

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
 * The read and write rules, with no database.
 *
 * <p>The reservation rules themselves are in {@link StockReservationTest}, alongside the bean that
 * implements them - the retry lives here and the transaction lives there, and testing each where it
 * is keeps this class from having to explain both.
 */
@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    @Mock
    private StockRepository stockRepository;

    @Mock
    private StockReservation stockReservation;

    @InjectMocks
    private StockService stockService;

    @Nested
    class FindByProductId {

        @Test
        void findByProductId_whenTheRowExists_returnsTheQuantity() {
            // GIVEN
            given(stockRepository.findById(3L)).willReturn(Optional.of(new StockLevel(3L, 15)));

            // WHEN / THEN
            assertThat(stockService.findByProductId(3L).quantity()).isEqualTo(15);
        }

        @Test
        void findByProductId_whenThereIsNoRow_returnsZeroRatherThanThrowing() {
            // GIVEN a product this service has never heard of - which, with no foreign key to the
            // catalogue, is indistinguishable from a product created a second ago
            given(stockRepository.findById(404L)).willReturn(Optional.empty());

            // WHEN / THEN zero available, not a 404. Answering "not found" would turn an ordinary
            // intermediate state into an error on the catalogue page.
            assertThat(stockService.findByProductId(404L))
                    .isEqualTo(new StockResponse(404L, 0));
        }
    }

    @Nested
    class FindByProductIds {

        @Test
        void findByProductIds_always_answersForEveryIdAskedAbout() {
            // GIVEN two ids, only one of which has a row
            given(stockRepository.findAllByProductIdIn(List.of(1L, 2L)))
                    .willReturn(List.of(new StockLevel(1L, 5)));

            // WHEN
            List<StockResponse> stock = stockService.findByProductIds(List.of(1L, 2L));

            // THEN both come back, so a caller can line the answers up against what it asked for
            // without having to work out which ids went missing
            assertThat(stock).containsExactlyInAnyOrder(
                    new StockResponse(1L, 5),
                    new StockResponse(2L, 0));
        }
    }

    @Nested
    class SetQuantity {

        @Test
        void setQuantity_whenTheRowExists_updatesItInPlace() {
            // GIVEN
            StockLevel existing = new StockLevel(3L, 5);
            given(stockRepository.findById(3L)).willReturn(Optional.of(existing));
            given(stockRepository.save(existing)).willReturn(existing);

            // WHEN
            StockResponse updated = stockService.setQuantity(3L, new StockLevelRequest(42));

            // THEN
            assertThat(updated.quantity()).isEqualTo(42);
        }

        @Test
        void setQuantity_whenThereIsNoRow_createsOne() {
            // GIVEN a product with no stock row yet
            given(stockRepository.findById(77L)).willReturn(Optional.empty());
            given(stockRepository.save(any(StockLevel.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // WHEN
            StockResponse created = stockService.setQuantity(77L, new StockLevelRequest(9));

            // THEN an upsert, so the caller does not have to know whether the row existed - and so
            // the call is safe to retry, which matters for anything reachable over a network
            assertThat(created).isEqualTo(new StockResponse(77L, 9));
        }
    }

    @Nested
    class Reserve {

        @Test
        void reserve_always_delegatesToTheTransactionalBean() {
            // GIVEN
            ReservationRequest request = new ReservationRequest(
                    "order-1", List.of(new ReservationRequest.Line(1L, 2)));

            // WHEN
            stockService.reserve(request);

            // THEN it goes to a DIFFERENT bean, which is the whole point: calling a @Transactional
            // method on `this` would bypass the proxy and run with no transaction at all, silently.
            verify(stockReservation).reserveOnce(request);
            verify(stockReservation, never()).releaseOnce(any());
        }

        @Test
        void reserve_whenTheReservationIsRefused_letsTheConflictOut() {
            // GIVEN not enough stock
            ReservationRequest request = new ReservationRequest(
                    "order-2", List.of(new ReservationRequest.Line(1L, 99)));
            org.mockito.BDDMockito.willThrow(new ConflictException("Only 1 unit(s) of product 1 in stock, requested 99"))
                    .given(stockReservation).reserveOnce(request);

            // WHEN / THEN it is not swallowed or retried into something else - GlobalExceptionHandler
            // turns it into the 409 that tells a shopper their cart no longer works
            assertThatThrownBy(() -> stockService.reserve(request))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("requested 99");
        }
    }
}
