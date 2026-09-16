package com.ecomdemo.order.client;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Builds the REAL client proxies and asserts what they put on the wire.
 *
 * <h2>Why this class exists, which is worth being blunt about</h2>
 *
 * Every other test in this service mocks {@link CatalogClient} and {@link InventoryClient} at the
 * interface, because what those tests are about is how order-service behaves given an answer. That
 * is the right call for them and it left a hole exactly the size of this class: nothing ever asked
 * {@code HttpServiceProxyFactory} to build an implementation, so nothing noticed that the interface
 * could not be implemented.
 *
 * <p>Specifically: {@code findById(Long id)} had no {@code @PathVariable}. An HTTP service interface
 * resolves nothing by convention, and the failure is not at startup - the proxy builds fine - but at
 * the first call, with {@code "Could not resolve parameter [0] ... No suitable resolver"}. The whole
 * module was green, all five services started healthy, and the first add-to-cart returned a 500.
 *
 * <p>It was found by running the stack, which is the part of "Done when" that no amount of unit
 * testing replaces. This class is what stops it being found that way twice.
 *
 * <p>{@link MockRestServiceServer} intercepts at the {@code RestClient} level, so the proxy, the
 * argument resolvers, the URI templating and the JSON conversion are all real - only the socket is
 * not.
 */
class ServiceClientsTest {

    private final ServiceClientsConfiguration configuration = new ServiceClientsConfiguration();

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void bindAMockServer() {
        builder = RestClient.builder().baseUrl("http://catalog-service:8082");
        server = MockRestServiceServer.bindTo(builder).build();
    }

    /** Builds a client over the intercepted builder, the way the configuration builds the real one. */
    private <T> T clientOver(Class<T> type, String basePath) {
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(type);
    }

    @Nested
    class TheCatalogClient {

        @Test
        void findById_always_issuesAGetToTheProductsPathWithTheIdSubstituted() {
            // GIVEN catalog-service answering for product 1
            server.expect(requestTo("http://catalog-service:8082/api/products/1"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("""
                            {"id":1,"name":"Mechanical Keyboard","price":129.99}""",
                            MediaType.APPLICATION_JSON));

            // WHEN the real proxy is asked
            CatalogProduct product = clientOver(CatalogClient.class, "/api/products").findById(1L);

            // THEN the id reached the URL - which is what @PathVariable is for, and what its absence
            // silently failed to do
            server.verify();
            assertThat(product.id()).isEqualTo(1L);
            assertThat(product.price()).isEqualByComparingTo("129.99");
        }

        @Test
        void findById_always_ignoresFieldsItDoesNotKnowAbout() {
            // GIVEN a response carrying fields this service has never heard of - which is what a
            // catalog-service deployed after this one looks like
            server.expect(requestTo("http://catalog-service:8082/api/products/1"))
                    .andRespond(withSuccess("""
                            {"id":1,"name":"Mechanical Keyboard","price":129.99,
                             "category":"Peripherals","description":"...","weightGrams":880,
                             "somethingAddedNextYear":{"nested":true}}""",
                            MediaType.APPLICATION_JSON));

            // WHEN
            CatalogProduct product = clientOver(CatalogClient.class, "/api/products").findById(1L);

            // THEN it reads what it needs and ignores the rest. This is the entire versioning
            // strategy between the two services: catalog-service can add fields and deploy whenever
            // it likes, without waiting for this one. Without @JsonIgnoreProperties every additive
            // change upstream would be a breaking change here.
            assertThat(product.name()).isEqualTo("Mechanical Keyboard");
        }

        @Test
        void findAll_always_issuesOneGetForTheWholeCatalogue() {
            // GIVEN
            server.expect(requestTo("http://catalog-service:8082/api/products"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("""
                            [{"id":1,"name":"Keyboard","price":129.99},
                             {"id":2,"name":"Mouse","price":49.50}]""",
                            MediaType.APPLICATION_JSON));

            // WHEN
            List<CatalogProduct> products = clientOver(CatalogClient.class, "/api/products").findAll();

            // THEN one request, two products. One call per cart rather than one per line is the
            // difference between a page load and an N+1 across a network.
            server.verify();
            assertThat(products).hasSize(2);
        }
    }

    @Nested
    class TheInventoryClient {

        @Test
        void reserve_always_postsTheWholeCartAsJson() {
            // GIVEN inventory-service accepting a reservation
            server.expect(requestTo("http://catalog-service:8082/api/stock/reservations"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.orderReference").value("customer-42"))
                    .andExpect(jsonPath("$.lines[0].productId").value(1))
                    .andExpect(jsonPath("$.lines[0].quantity").value(2))
                    .andExpect(jsonPath("$.lines[1].productId").value(3))
                    .andRespond(withSuccess());

            // WHEN
            clientOver(InventoryClient.class, "/api/stock").reserve(new ReservationRequest(
                    "customer-42",
                    List.of(new ReservationRequest.Line(1L, 2), new ReservationRequest.Line(3L, 1))));

            // THEN the body is the shape inventory-service's ReservationRequest reads - a contract
            // held by these field names, since neither service compiles against the other's record.
            // And both lines are in ONE request, which is what keeps the reservation all-or-nothing.
            server.verify();
        }

        @Test
        void release_always_postsToTheReleasesPath() {
            // GIVEN
            server.expect(requestTo("http://catalog-service:8082/api/stock/releases"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess());

            // WHEN
            clientOver(InventoryClient.class, "/api/stock").release(new ReservationRequest(
                    "customer-42", List.of(new ReservationRequest.Line(1L, 2))));

            // THEN
            server.verify();
        }
    }

    @Nested
    class TokenPropagation {

        @Test
        void anyCall_whenTheCallerPresentedAToken_forwardsIt() {
            // GIVEN a request thread carrying a verified token, as the filter chain leaves it
            Jwt jwt = Jwt.withTokenValue("the-callers-token")
                    .header("alg", "RS256")
                    .claim("sub", "42")
                    .build();
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated("principal", jwt, List.of()));

            try {
                RestClient.Builder withInterceptor = RestClient.builder()
                        .baseUrl("http://catalog-service:8082")
                        .requestInterceptor(configuration.bearerTokenPropagation());
                MockRestServiceServer interceptedServer =
                        MockRestServiceServer.bindTo(withInterceptor).build();

                interceptedServer.expect(requestTo("http://catalog-service:8082/api/products/1"))
                        // THEN the shopper's own token goes on the outbound request, unchanged.
                        //
                        // order-service holds no service credential of its own: the service being
                        // called authorizes the PERSON on whose behalf the work is happening, so
                        // identity survives the hop and a compromised order-service can do exactly
                        // what its callers could do and no more.
                        .andExpect(header("Authorization", "Bearer the-callers-token"))
                        .andRespond(withSuccess("""
                                {"id":1,"name":"Keyboard","price":129.99}""",
                                MediaType.APPLICATION_JSON));

                HttpServiceProxyFactory
                        .builderFor(RestClientAdapter.create(withInterceptor.build()))
                        .build()
                        .createClient(CatalogClient.class)
                        .findById(1L);

                interceptedServer.verify();
            } finally {
                // SecurityContextHolder is thread-local and JUnit reuses the thread.
                SecurityContextHolder.clearContext();
            }
        }

        @Test
        void anyCall_whenThereIsNoAuthenticatedCaller_sendsNoAuthorizationHeader() {
            // GIVEN no security context - a call made on nobody's behalf, from a background thread
            SecurityContextHolder.clearContext();

            RestClient.Builder withInterceptor = RestClient.builder()
                    .baseUrl("http://catalog-service:8082")
                    .requestInterceptor(configuration.bearerTokenPropagation());
            MockRestServiceServer interceptedServer = MockRestServiceServer.bindTo(withInterceptor).build();

            interceptedServer.expect(requestTo("http://catalog-service:8082/api/products/1"))
                    .andExpect(request -> assertThat(request.getHeaders().getFirst("Authorization"))
                            // THEN nothing is invented. The call goes out anonymous and the service
                            // on the other side refuses it if it needs a token - which is the right
                            // failure for work nobody asked for.
                            .isNull())
                    .andRespond(withSuccess("""
                            {"id":1,"name":"Keyboard","price":129.99}""",
                            MediaType.APPLICATION_JSON));

            HttpServiceProxyFactory
                    .builderFor(RestClientAdapter.create(withInterceptor.build()))
                    .build()
                    .createClient(CatalogClient.class)
                    .findById(1L);

            interceptedServer.verify();
        }
    }
}
