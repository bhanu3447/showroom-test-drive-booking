package dev.showroom.testdrive.booking;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import dev.showroom.testdrive.catalog.Car;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "car_id")
    private Car car;

    @Column(name = "customer_name")
    private String customerName;
    private String phone;
    private String email;
    @Column(name = "slot_start")
    private LocalDateTime slotStart;

    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Optimistic locking: two concurrent cancels cannot both win. Null until first save, so save() persists. */
    @Version
    private Long version;

    protected Booking() {
    }

    static Booking confirm(Car car, String customerName, String phone, String email, LocalDateTime slotStart, Instant now) {
        Booking b = new Booking();
        b.id = UUID.randomUUID();
        b.car = car;
        b.customerName = customerName;
        b.phone = phone;
        b.email = email;
        b.slotStart = slotStart;
        b.status = BookingStatus.CONFIRMED;
        b.createdAt = now;
        b.updatedAt = now;
        return b;
    }

    void cancel(Instant now) {
        status = BookingStatus.CANCELLED;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public Car getCar() { return car; }
    public String getCustomerName() { return customerName; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public LocalDateTime getSlotStart() { return slotStart; }
    public BookingStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
