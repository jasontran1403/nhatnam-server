package com.nhatnam.server.tools.vmb.repository;

import com.nhatnam.server.tools.vmb.entity.BookingProof;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BookingProofRepository extends JpaRepository<BookingProof, Long> {

    @Query("""
        SELECT p FROM BookingProof p
        WHERE p.booking.id = :bookingId
        ORDER BY p.id ASC
    """)
    List<BookingProof> findByBookingId(@Param("bookingId") Long bookingId);
}