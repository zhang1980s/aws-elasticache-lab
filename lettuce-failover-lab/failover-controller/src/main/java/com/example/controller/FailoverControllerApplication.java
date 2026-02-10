package com.example.controller;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FailoverControllerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FailoverControllerApplication.class, args);
    }
}
