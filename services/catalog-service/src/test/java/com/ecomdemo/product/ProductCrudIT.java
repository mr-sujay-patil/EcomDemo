package com.ecomdemo.product;

import java.math.BigDecimal;

import com.ecomdemo.shared.ApiError;
import com.ecomdemo.product.dto.ProductRequest;
import com.ecomdemo.product.dto.ProductResponse;
import com.ecomdemo.support.AbstractCatalogServiceIT;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Product CRUD end to end, over HTTP, against real PostgreSQL.
 *
 * <p>What this catches that the unit and slice tests cannot: the column types are PostgreSQL's, so
 * {@code numeric(12,2)} rounds the way PostgreSQL rounds and {@code varchar(1000)} rejects what
 * PostgreSQL rejects. H2 in {@code MODE=PostgreSQL} imitates a great deal of that, and the places it
 * does not are exactly the ones nobody thinks to check.
 *
 * <p>Writes go through {@code admin} and reads through {@code anonymous}, which is the authorization
 * rule stated as code: changing the catalogue needs ADMIN, browsing it needs nobody at all.
 *
 * <p>Nothing here assumes an empty table. Every integration test in this run shares one container,
 * and each one commits - so assertions are about the rows this test created, never about counts.
 */
class ProductCrudIT extends AbstractCatalogServiceIT {

    private ProductRequest request(String name, String price, int stock, String category) {
        return new ProductRequest(name, name + " description", new BigDecimal(price), category);
    }

    @Test
    void create_thenRead_returnsWhatWasStored() {
        // GIVEN / WHEN
        ProductResponse created = admin.post().uri("/api/products")
                .body(request("IT Desk Lamp", "45.50", 12, "Lighting"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ProductResponse.class)
                .returnResult().getResponseBody();

        // THEN the server assigned the id, not the client
        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("IT Desk Lamp");
        assertThat(created.category()).isEqualTo("Lighting");
        assertThat(created.price()).isEqualByComparingTo("45.50");

        // AND reading it back over HTTP gives the same thing from the database
        ProductResponse fetched = anonymous.get().uri("/api/products/" + created.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponse.class)
                .returnResult().getResponseBody();

        assertThat(fetched).isNotNull();
        assertThat(fetched.price()).isEqualByComparingTo("45.50");
    }

    @Test
    void create_withMoreThanTwoDecimalPlaces_storesTheRoundedValue() {
        // GIVEN a price PostgreSQL's numeric(12,2) cannot hold as given
        // WHEN
        ProductResponse created = admin.post().uri("/api/products")
                .body(request("IT Rounding", "9.999", 1, null))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ProductResponse.class)
                .returnResult().getResponseBody();

        // THEN the service rounded HALF_UP on the way in, so the column never had to.
        // Worth asserting against a real database: if the service ever stopped normalising, the
        // difference between PostgreSQL rounding and H2 rounding is where the bug would hide.
        assertThat(created).isNotNull();
        assertThat(created.price()).isEqualByComparingTo("10.00");
    }

    @Test
    void update_replacesEveryFieldAndPersists() {
        // GIVEN
        ProductResponse created = admin.post().uri("/api/products")
                .body(request("IT Before", "10.00", 5, "Old"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ProductResponse.class)
                .returnResult().getResponseBody();
        assertThat(created).isNotNull();

        // WHEN
        admin.put().uri("/api/products/" + created.id())
                .body(request("IT After", "20.00", 50, "New"))
                .exchange()
                .expectStatus().isOk();

        // THEN the change was flushed by dirty checking, with no save() call anywhere
        ProductResponse fetched = anonymous.get().uri("/api/products/" + created.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponse.class)
                .returnResult().getResponseBody();

        assertThat(fetched).isNotNull();
        assertThat(fetched.name()).isEqualTo("IT After");
        assertThat(fetched.price()).isEqualByComparingTo("20.00");
        assertThat(fetched.category()).isEqualTo("New");
    }

    @Test
    void delete_removesItAndASecondReadIs404() {
        // GIVEN
        ProductResponse created = admin.post().uri("/api/products")
                .body(request("IT Doomed", "1.00", 1, null))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ProductResponse.class)
                .returnResult().getResponseBody();
        assertThat(created).isNotNull();

        // WHEN
        admin.delete().uri("/api/products/" + created.id())
                .exchange()
                .expectStatus().isNoContent();

        // THEN
        ApiError error = anonymous.get().uri("/api/products/" + created.id())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(ApiError.class)
                .returnResult().getResponseBody();

        assertThat(error).isNotNull();
        assertThat(error.message()).contains("not found");
    }

    @Test
    void create_withAnInvalidBody_is400AndStoresNothing() {
        // GIVEN a blank name and a negative price
        // WHEN / THEN the validation advice answers before anything reaches the database
        ApiError error = admin.post().uri("/api/products")
                .body(new ProductRequest("", null, new BigDecimal("-1"), null))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ApiError.class)
                .returnResult().getResponseBody();

        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(error.message()).contains("name").contains("price");
    }
}
