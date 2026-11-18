package dev.showroom.testdrive.booking;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlotHoldRepository extends JpaRepository<SlotHold, SlotKey> {

    @Query("select h.id.slotStart from SlotHold h where h.id.carId = :carId and h.id.slotStart >= :from and h.id.slotStart < :to")
    List<LocalDateTime> takenSlots(@Param("carId") long carId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Modifying
    @Query("delete from SlotHold h where h.bookingId = :bookingId")
    int releaseFor(@Param("bookingId") UUID bookingId);
}
