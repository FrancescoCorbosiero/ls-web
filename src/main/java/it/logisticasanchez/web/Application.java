package it.logisticasanchez.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Logistica Sanchez web app: public site, customer area, back office and
 * API in one deployable (chapter 10 §2). Modules live in sub-packages and talk to each other
 * only through their services.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
