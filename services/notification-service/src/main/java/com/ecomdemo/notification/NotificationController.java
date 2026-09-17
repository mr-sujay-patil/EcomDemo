package com.ecomdemo.notification;

import java.util.List;

import com.ecomdemo.shared.security.SecurityUser;
import com.ecomdemo.notification.dto.NotificationResponse;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The confirmations this customer has been sent.
 *
 * <p>Read-only and deliberately small. Notifications are created by consuming an event, never by an
 * HTTP request - there is no POST here and there should not be one. What it buys is that "the
 * notification was recorded, once" can be checked from outside the application, which is the
 * difference between a demo you can run and a log line you have to trust.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** The principal carries the id; the service takes it as a parameter and never reads the context. */
    @GetMapping
    public List<NotificationResponse> findAll(@AuthenticationPrincipal SecurityUser user) {
        return notificationService.findAll(user.getId());
    }
}
