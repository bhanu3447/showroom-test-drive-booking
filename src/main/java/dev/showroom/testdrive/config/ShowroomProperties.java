package dev.showroom.testdrive.config;

import java.time.LocalTime;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Opening hours and slot rules of the showroom (app.showroom.*). */
@ConfigurationProperties("app.showroom")
public record ShowroomProperties(ZoneId zone, LocalTime opensAt, LocalTime closesAt, int slotMinutes, int maxDaysAhead) {
}
