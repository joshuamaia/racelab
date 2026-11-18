package dev.racelab.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OpenAPI raceLabOpenApi() {
        return new OpenAPI().info(new Info()
                .title("RaceLab API")
                .version("1.0.0")
                .description("Real-Time Concurrency & Transaction Laboratory — Understand concurrency. Prevent inconsistency. "
                        + "Every experiment runs real transactions on PostgreSQL.")
                .license(new License().name("MIT")));
    }
}
