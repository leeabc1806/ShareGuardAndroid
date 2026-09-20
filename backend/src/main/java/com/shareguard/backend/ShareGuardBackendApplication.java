package com.shareguard.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ShareGuardBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShareGuardBackendApplication.class, args);
    }
}
