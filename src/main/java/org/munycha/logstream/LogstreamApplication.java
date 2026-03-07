package org.munycha.logstream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class LogstreamApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogstreamApplication.class, args);
    }
}
