package com.ecomdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The service that owns identity.
 *
 * <p>It is the only one with a users table, the only one that ever sees a password, and the only one
 * that can sign a token. The other four trust it without ever calling it: they fetch its public key
 * once and verify signatures locally, so customer-service being down stops new logins and nothing
 * else. That asymmetry - a hard dependency at login, none afterwards - is the most valuable property
 * in this phase's design, and it comes entirely from choosing RS256 over a shared secret.
 *
 * <p>The class sits in {@code com.ecomdemo} rather than {@code com.ecomdemo.customer} so that the
 * default component scan covers both {@code customer} and {@code auth}. shared-kernel's beans do not
 * need scanning at all - they arrive through auto-configuration, which is why this service can keep
 * its own packages and still get the error shape and the filter chain.
 */
@SpringBootApplication
public class CustomerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerServiceApplication.class, args);
    }
}
