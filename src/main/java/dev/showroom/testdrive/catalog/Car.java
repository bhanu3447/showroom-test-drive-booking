package dev.showroom.testdrive.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "cars")
public class Car {

    @Id
    private Long id;
    private String model;
    @Column(name = "body_type")
    private String bodyType;
    private String fuel;
    @Column(name = "ex_showroom_price_inr")
    private long exShowroomPriceInr;
    private boolean active;

    protected Car() {
    }

    public Car(Long id, String model, String bodyType, String fuel, long exShowroomPriceInr, boolean active) {
        this.id = id;
        this.model = model;
        this.bodyType = bodyType;
        this.fuel = fuel;
        this.exShowroomPriceInr = exShowroomPriceInr;
        this.active = active;
    }

    public Long getId() { return id; }
    public String getModel() { return model; }
    public String getBodyType() { return bodyType; }
    public String getFuel() { return fuel; }
    public long getExShowroomPriceInr() { return exShowroomPriceInr; }
    public boolean isActive() { return active; }
}
