package com.ecomdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the monolith.
 *
 * <p>{@code @SpringBootApplication} bundles three annotations:
 * {@code @SpringBootConfiguration} (this class is a bean definition source),
 * {@code @ComponentScan} (find {@code @RestController}, {@code @Service} and friends under
 * {@code com.ecomdemo}) and {@code @EnableAutoConfiguration} (configure Tomcat, Jackson, Hibernate
 * and the H2 DataSource based only on what is present on the classpath).
 */
@SpringBootApplication
public class EcomDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(EcomDemoApplication.class, args);
    }
}
