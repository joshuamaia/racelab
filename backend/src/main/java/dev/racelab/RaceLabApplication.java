package dev.racelab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** RaceLab — Understand concurrency. Prevent inconsistency. */
@SpringBootApplication
public class RaceLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(RaceLabApplication.class, args);
    }
}
