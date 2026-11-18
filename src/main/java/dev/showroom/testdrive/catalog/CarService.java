package dev.showroom.testdrive.catalog;

import java.util.List;

import dev.showroom.testdrive.web.NotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalogue is read on every page view and changes rarely: cache-aside in Redis
 * (an in-process map in the 'local' profile), 10 minute TTL.
 */
@Service
@Transactional(readOnly = true)
public class CarService {

    private final CarRepository cars;

    public CarService(CarRepository cars) {
        this.cars = cars;
    }

    @Cacheable(cacheNames = "cars", key = "'active'")
    public List<CarResponse> activeCars() {
        return cars.findByActiveTrueOrderByIdAsc().stream().map(CarResponse::from).toList();
    }

    @Cacheable(cacheNames = "car", key = "#id")
    public CarResponse car(long id) {
        return CarResponse.from(activeCar(id));
    }

    /** For the booking flow: entity, not cached, must be on the floor (active). */
    public Car activeCar(long id) {
        return cars.findById(id).filter(Car::isActive)
                .orElseThrow(() -> new NotFoundException("car-not-found", "No car " + id + " on the showroom floor"));
    }
}
