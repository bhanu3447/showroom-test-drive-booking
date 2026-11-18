package dev.showroom.testdrive.catalog;

import java.util.List;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cars")
@Tag(name = "Cars", description = "The showroom floor")
public class CarController {

    private final CarService cars;

    public CarController(CarService cars) {
        this.cars = cars;
    }

    @GetMapping
    public List<CarResponse> list() {
        return cars.activeCars();
    }

    @GetMapping("/{id}")
    public CarResponse get(@PathVariable long id) {
        return cars.car(id);
    }
}
