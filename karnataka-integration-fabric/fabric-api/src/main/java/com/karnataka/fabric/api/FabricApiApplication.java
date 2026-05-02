package com.karnataka.fabric.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main entry point for the Karnataka Integration Fabric REST API.
 */
@SpringBootApplication(scanBasePackages = "com.karnataka.fabric")
@EntityScan(basePackages = "com.karnataka.fabric")
@EnableJpaRepositories(basePackages = "com.karnataka.fabric")
@EnableScheduling
public class FabricApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(FabricApiApplication.class, args);
    }
}
