package io.labdeck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class LabDeckApplication {

    public static void main(String[] args) {
        SpringApplication.run(LabDeckApplication.class, args);
    }
}
