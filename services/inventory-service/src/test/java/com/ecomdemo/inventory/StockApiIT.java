package com.ecomdemo.inventory;

import java.util.List;

import com.ecomdemo.inventory.dto.ReservationRequest;
import com.ecomdemo.inventory.dto.StockLevelRequest;
import com.ecomdemo.inventory.dto.StockResponse;
import com.ecomdemo.support.AbstractInventoryServiceIT;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP contract order-service depends on, exercised over a real socket against real PostgreSQL.
 *
 * <p>These assertions are a contract in the literal sense: order-service's {@code InventoryClient}
 * is written against exactly these paths, bodies and status codes, and it is in another module that
 * cannot import them. Nothing but a convention keeps the two in step, which is why the shapes are
 * pinned here rather than left implicit in whichever test happened to exercise them.
 */
class StockApiIT extends AbstractInventoryServiceIT {

    @Nested
    class ReadingStock {

        @Test
        void get_always_needsNoToken() {
            // GIVEN a product with stock set
            Long productId = freshProductId();
            admin.put().uri("/api/stock/" + productId)
                    .body(new StockLevelRequest(12))
                    .exchange().expectStatus().isOk();

            // WHEN an anonymous caller asks
            StockResponse stock = anonymous.get().uri("/api/stock/" + productId)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(StockResponse.class)
                    .returnResult().getResponseBody();

            // THEN availability is as public as the catalogue it describes
            assertThat(stock).isEqualTo(new StockResponse(productId, 12));
        }

        @Test
        void get_forAProductWithNoRow_is200WithZeroRatherThan404() {
            // GIVEN a product id this service has never seen. With no foreign key to catalog-service
            // that is indistinguishable from a product created a second ago, and it is not an error.
            Long unknown = freshProductId();

            // WHEN / THEN
            assertThat(anonymous.get().uri("/api/stock/" + unknown)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(StockResponse.class)
                    .returnResult().getResponseBody())
                    .isEqualTo(new StockResponse(unknown, 0));
        }

        @Test
        void getMany_always_answersForEveryIdAskedAbout() {
            // GIVEN one product with stock and one without
            Long known = freshProductId();
            Long unknown = known + 1;
            admin.put().uri("/api/stock/" + known).body(new StockLevelRequest(4))
                    .exchange().expectStatus().isOk();

            // WHEN both are asked for in one call - the shape a page of products needs, and the
            // difference between one request and ten once the loop crosses a network
            // RestTestClient has no expectBodyList: expectBody takes a Class or a
            // ParameterizedTypeReference, and an array type is the shorter of the two here.
            StockResponse[] stock = anonymous.get()
                    .uri("/api/stock?productIds=" + known + "," + unknown)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(StockResponse[].class)
                    .returnResult().getResponseBody();

            // THEN
            assertThat(stock).containsExactlyInAnyOrder(
                    new StockResponse(known, 4),
                    new StockResponse(unknown, 0));
        }
    }

    @Nested
    class SettingStock {

        @Test
        void put_asAdmin_createsTheRowOnFirstUseAndUpdatesItAfterwards() {
            // GIVEN a product with no stock row
            Long productId = freshProductId();

            // WHEN set twice
            admin.put().uri("/api/stock/" + productId).body(new StockLevelRequest(7))
                    .exchange().expectStatus().isOk();
            admin.put().uri("/api/stock/" + productId).body(new StockLevelRequest(7))
                    .exchange().expectStatus().isOk();

            // THEN an upsert with an absolute figure, so repeating it is harmless. A delta ("add 7")
            // would have made this 14, and a client that retried a request whose response it never
            // saw would silently double the stock.
            assertThat(anonymous.get().uri("/api/stock/" + productId)
                    .exchange().expectBody(StockResponse.class)
                    .returnResult().getResponseBody().quantity())
                    .isEqualTo(7);
        }

        @Test
        void put_asCustomer_is403() {
            customer.put().uri("/api/stock/" + freshProductId())
                    .body(new StockLevelRequest(999))
                    .exchange().expectStatus().isForbidden();
        }

        @Test
        void put_withNoToken_is401() {
            anonymous.put().uri("/api/stock/" + freshProductId())
                    .body(new StockLevelRequest(999))
                    .exchange().expectStatus().isUnauthorized();
        }

        @Test
        void put_withANegativeQuantity_is400() {
            admin.put().uri("/api/stock/" + freshProductId())
                    .body(new StockLevelRequest(-1))
                    .exchange().expectStatus().isBadRequest();
        }
    }

    @Nested
    class ReservingStock {

        private ReservationRequest oneOf(Long productId, int quantity) {
            return new ReservationRequest("it-order",
                    List.of(new ReservationRequest.Line(productId, quantity)));
        }

        @Test
        void reserve_withEnoughStock_is204AndReducesIt() {
            // GIVEN
            Long productId = freshProductId();
            admin.put().uri("/api/stock/" + productId).body(new StockLevelRequest(10))
                    .exchange().expectStatus().isOk();

            // WHEN order-service reserves on a shopper's behalf, carrying that shopper's own token
            customer.post().uri("/api/stock/reservations").body(oneOf(productId, 3))
                    .exchange().expectStatus().isNoContent();

            // THEN
            assertThat(anonymous.get().uri("/api/stock/" + productId)
                    .exchange().expectBody(StockResponse.class)
                    .returnResult().getResponseBody().quantity())
                    .isEqualTo(7);
        }

        @Test
        void reserve_withoutEnoughStock_is409AndChangesNothing() {
            // GIVEN two units
            Long productId = freshProductId();
            admin.put().uri("/api/stock/" + productId).body(new StockLevelRequest(2))
                    .exchange().expectStatus().isOk();

            // WHEN five are asked for
            customer.post().uri("/api/stock/reservations").body(oneOf(productId, 5))
                    .exchange().expectStatus().isEqualTo(409);

            // THEN 409, not 400: the request was perfectly well formed, the server's state simply
            // does not allow it. And nothing was taken.
            assertThat(anonymous.get().uri("/api/stock/" + productId)
                    .exchange().expectBody(StockResponse.class)
                    .returnResult().getResponseBody().quantity())
                    .isEqualTo(2);
        }

        @Test
        void reserve_whenOneLineOfManyIsShort_leavesEveryLineUntouched() {
            // GIVEN two products, the second of which cannot cover what is asked
            Long plenty = freshProductId();
            Long scarce = plenty + 1;
            admin.put().uri("/api/stock/" + plenty).body(new StockLevelRequest(10))
                    .exchange().expectStatus().isOk();
            admin.put().uri("/api/stock/" + scarce).body(new StockLevelRequest(1))
                    .exchange().expectStatus().isOk();

            // WHEN a two-line reservation is attempted
            customer.post().uri("/api/stock/reservations")
                    .body(new ReservationRequest("it-order", List.of(
                            new ReservationRequest.Line(plenty, 2),
                            new ReservationRequest.Line(scarce, 5))))
                    .exchange().expectStatus().isEqualTo(409);

            // THEN neither line moved. This is Phase 0's "validate the whole cart before mutating
            // anything" surviving the move behind an HTTP call - the property most easily lost by
            // splitting a service, because the obvious API is one line per request.
            assertThat(anonymous.get().uri("/api/stock/" + plenty)
                    .exchange().expectBody(StockResponse.class)
                    .returnResult().getResponseBody().quantity())
                    .isEqualTo(10);
        }

        @Test
        void release_always_putsTheUnitsBack() {
            // GIVEN stock that has been reserved
            Long productId = freshProductId();
            admin.put().uri("/api/stock/" + productId).body(new StockLevelRequest(10))
                    .exchange().expectStatus().isOk();
            customer.post().uri("/api/stock/reservations").body(oneOf(productId, 4))
                    .exchange().expectStatus().isNoContent();

            // WHEN the compensating call arrives - what order-service does when it reserved stock and
            // then failed to save the order
            customer.post().uri("/api/stock/releases").body(oneOf(productId, 4))
                    .exchange().expectStatus().isNoContent();

            // THEN
            assertThat(anonymous.get().uri("/api/stock/" + productId)
                    .exchange().expectBody(StockResponse.class)
                    .returnResult().getResponseBody().quantity())
                    .isEqualTo(10);
        }

        @Test
        void reserve_withNoToken_is401() {
            anonymous.post().uri("/api/stock/reservations").body(oneOf(freshProductId(), 1))
                    .exchange().expectStatus().isUnauthorized();
        }

        @Test
        void reserve_withAnEmptyLineList_is400() {
            customer.post().uri("/api/stock/reservations")
                    .body(new ReservationRequest("it-order", List.of()))
                    .exchange().expectStatus().isBadRequest();
        }
    }
}
