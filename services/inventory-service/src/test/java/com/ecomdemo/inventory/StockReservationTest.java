package com.ecomdemo.inventory;

import java.util.List;

import com.ecomdemo.inventory.dto.ReservationRequest;
import com.ecomdemo.shared.ConflictException;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * The rules of a reservation: all of it or none of it.
 *
 * <p>The most important test in this service is {@code reserve_whenOneLineIsShort_changesNothingAtAll}.
 * Checkout in Phase 0 validated the whole cart before mutating anything, so a five-line order whose
 * fourth line was short changed nothing; moving stock behind an HTTP call is exactly the kind of
 * change that quietly loses that property, and this is what stops it.
 */
@ExtendWith(MockitoExtension.class)
class StockReservationTest {

    @Mock
    private StockRepository stockRepository;

    @InjectMocks
    private StockReservation stockReservation;

    private static ReservationRequest reservationOf(ReservationRequest.Line... lines) {
        return new ReservationRequest("order-42", List.of(lines));
    }

    @Nested
    class ReserveOnce {

        @Test
        void reserveOnce_whenEveryLineIsAvailable_reducesAllOfThem() {
            // GIVEN two products with plenty in stock
            StockLevel keyboard = new StockLevel(1L, 10);
            StockLevel mouse = new StockLevel(2L, 4);
            given(stockRepository.findAllByProductIdIn(List.of(1L, 2L)))
                    .willReturn(List.of(keyboard, mouse));

            // WHEN
            stockReservation.reserveOnce(reservationOf(
                    new ReservationRequest.Line(1L, 3),
                    new ReservationRequest.Line(2L, 4)));

            // THEN both are reduced. No save() call is asserted: the entities are managed inside the
            // transaction, so Hibernate flushes the changes by dirty checking.
            assertThat(keyboard.getQuantity()).isEqualTo(7);
            assertThat(mouse.getQuantity()).isZero();
        }

        @Test
        void reserveOnce_whenOneLineIsShort_changesNothingAtAll() {
            // GIVEN a three-line reservation whose LAST line cannot be satisfied
            StockLevel first = new StockLevel(1L, 10);
            StockLevel second = new StockLevel(2L, 10);
            StockLevel third = new StockLevel(3L, 1);
            given(stockRepository.findAllByProductIdIn(List.of(1L, 2L, 3L)))
                    .willReturn(List.of(first, second, third));

            // WHEN
            assertThatThrownBy(() -> stockReservation.reserveOnce(reservationOf(
                    new ReservationRequest.Line(1L, 2),
                    new ReservationRequest.Line(2L, 2),
                    new ReservationRequest.Line(3L, 5))))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Only 1 unit(s) of product 3");

            // THEN the FIRST two lines are untouched. Every line is checked before any is written,
            // so a refused reservation leaves nothing half-applied - which matters more than ever
            // now that the caller is in another process and could not put it back reliably.
            assertThat(first.getQuantity()).isEqualTo(10);
            assertThat(second.getQuantity()).isEqualTo(10);
            assertThat(third.getQuantity()).isEqualTo(1);
        }

        @Test
        void reserveOnce_whenAProductHasNoStockRow_isRefusedAsZeroAvailable() {
            // GIVEN a product id this service has never seen - a catalogue entry whose stock was
            // never set, which with no foreign key between the databases is entirely possible
            given(stockRepository.findAllByProductIdIn(List.of(404L))).willReturn(List.of());

            // WHEN / THEN refused, and the message says zero rather than pretending the product is
            // unknown. To a shopper the two are the same thing: you cannot buy it right now.
            assertThatThrownBy(() -> stockReservation.reserveOnce(
                    reservationOf(new ReservationRequest.Line(404L, 1))))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Only 0 unit(s) of product 404");
        }

        @Test
        void reserveOnce_whenTheSameProductAppearsTwice_readsTheRowOnce() {
            // GIVEN a request naming the same product on two lines
            StockLevel keyboard = new StockLevel(1L, 10);
            given(stockRepository.findAllByProductIdIn(List.of(1L))).willReturn(List.of(keyboard));

            // WHEN
            stockReservation.reserveOnce(reservationOf(
                    new ReservationRequest.Line(1L, 3),
                    new ReservationRequest.Line(1L, 2)));

            // THEN the id was de-duplicated for the query - the stub above would not have matched
            // otherwise - and both lines still came off the one row
            assertThat(keyboard.getQuantity()).isEqualTo(5);
        }
    }

    @Nested
    class ReleaseOnce {

        @Test
        void releaseOnce_always_putsTheUnitsBack() {
            // GIVEN stock that was reserved by a checkout which then failed
            StockLevel keyboard = new StockLevel(1L, 7);
            given(stockRepository.findAllByProductIdIn(List.of(1L))).willReturn(List.of(keyboard));

            // WHEN the compensating call arrives
            stockReservation.releaseOnce(reservationOf(new ReservationRequest.Line(1L, 3)));

            // THEN
            assertThat(keyboard.getQuantity()).isEqualTo(10);
        }

        @Test
        void releaseOnce_whenThereIsNoRowToReleaseInto_doesNotThrow() {
            // GIVEN a release naming a product with no stock row
            given(stockRepository.findAllByProductIdIn(List.of(404L))).willReturn(List.of());

            // WHEN / THEN it logs and carries on rather than failing. A release is a compensation for
            // something that has already gone wrong; throwing here would replace one problem with
            // two, and there is nobody left to handle the second.
            stockReservation.releaseOnce(reservationOf(new ReservationRequest.Line(404L, 1)));
        }
    }
}
