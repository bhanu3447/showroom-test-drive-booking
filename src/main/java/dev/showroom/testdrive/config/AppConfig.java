package dev.showroom.testdrive.config;

import java.time.Clock;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Caching and scheduling are switched on here, not on the main class, so test slices do not inherit them. */
@Configuration
@EnableCaching
@EnableScheduling
public class AppConfig {

    /** One clock for the whole app, so tests can pin "now". */
    @Bean
    Clock clock(ShowroomProperties showroom) {
        return Clock.system(showroom.zone());
    }

    @Bean
    OpenAPI showroomOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Showroom test-drive booking API")
                .version("v1")
                .description("Browse cars, see free test-drive slots, book (idempotent) and cancel."));
    }
}
