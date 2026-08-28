package com.codepilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class CodepilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodepilotApplication.class, args);
    }
}
