package dev.showroom.testdrive.catalog;

import java.io.Serializable;

/** What the API (and the Redis cache) holds for a car. Serializable for the cache. */
public record CarResponse(long id, String model, String bodyType, String fuel, long exShowroomPriceInr)
        implements Serializable {

    static CarResponse from(Car car) {
        return new CarResponse(car.getId(), car.getModel(), car.getBodyType(), car.getFuel(), car.getExShowroomPriceInr());
    }
}
