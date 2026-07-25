package com.riya.ratelimiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point.
 *
 * {@code @SpringBootApplication} is a convenience annotation that bundles:
 *   - @Configuration        (this class can define beans)
 *   - @EnableAutoConfiguration (Spring wires up Tomcat, Redis, Jackson, etc. based on the classpath)
 *   - @ComponentScan        (find @Component/@Service/@RestController in this package and below)
 */
@SpringBootApplication
public class RateLimiterApplication {

    public static void main(String[] args) {
        SpringApplication.run(RateLimiterApplication.class, args);
    }
}
