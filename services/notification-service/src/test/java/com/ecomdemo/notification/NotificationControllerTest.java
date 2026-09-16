package com.ecomdemo.notification;

import java.time.Instant;
import java.util.List;

import com.ecomdemo.notification.dto.NotificationResponse;
import com.ecomdemo.shared.testsupport.SecurityTestConfiguration;
import com.ecomdemo.shared.testsupport.WithMockCustomer;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** Slice tests for {@link NotificationController}. */
@WebMvcTest(NotificationController.class)
@Import({SecurityTestConfiguration.class, NotificationServiceAuthorizationRules.class})
class NotificationControllerTest {

    /** Matches the id in @WithMockCustomer, so the stub and the principal agree. */
    private static final Long CUSTOMER_ID = 42L;

    private static final Instant CREATED_AT = Instant.parse("2026-09-16T10:15:30Z");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    @WithMockCustomer(id = 42L)
    void list_whenNotificationsExist_returns200WithJsonArray() {
        // GIVEN
        given(notificationService.findAll(CUSTOMER_ID)).willReturn(List.of(
                new NotificationResponse(1L, 7L, "Thank you! Order #7 is confirmed.", CREATED_AT)));

        // WHEN / THEN
        assertThat(mvc.get().uri("/api/notifications"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$[0].orderId", id -> assertThat(id).isEqualTo(7))
                .hasPathSatisfying("$[0].message",
                        m -> assertThat(m).asString().contains("Order #7"))
                .hasPathSatisfying("$[0].createdAt",
                        at -> assertThat(at).asString().isEqualTo("2026-09-16T10:15:30Z"));
    }

    @Test
    @WithMockCustomer(id = 42L)
    void list_whenNothingHasBeenSent_returns200WithAnEmptyArray() {
        // GIVEN
        given(notificationService.findAll(CUSTOMER_ID)).willReturn(List.of());

        // WHEN / THEN - an empty list, not a 404. Asking for a collection that happens to be empty
        // is a perfectly successful request.
        assertThat(mvc.get().uri("/api/notifications"))
                .hasStatus(HttpStatus.OK)
                .bodyJson().isEqualTo("[]");
    }

    @Test
    void list_withoutAToken_returns401() {
        // GIVEN no authentication at all
        // WHEN / THEN every endpoint is denied by default; this one is not on the public list
        assertThat(mvc.get().uri("/api/notifications")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_asAnAdmin_returns403() {
        // GIVEN an authenticated administrator
        // WHEN / THEN confirmations belong to the customer they were sent to. An ADMIN is
        // authenticated but has no business reading someone's mail, and the rule says CUSTOMER.
        assertThat(mvc.get().uri("/api/notifications")).hasStatus(HttpStatus.FORBIDDEN);
    }
}
