package dev.showroom.testdrive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TestDriveApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestDriveApplication.class, args);
    }
}
