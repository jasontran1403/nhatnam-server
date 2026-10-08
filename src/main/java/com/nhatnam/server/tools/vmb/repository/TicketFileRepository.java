package com.nhatnam.server.tools.vmb.repository;

import com.nhatnam.server.tools.vmb.entity.TicketFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TicketFileRepository extends JpaRepository<TicketFile, Long> {

    /** Mặt vé chung của booking (ticket = null). Tối đa 1 dòng — trả Optional. */
    @Query("""
        SELECT f FROM TicketFile f
        WHERE f.booking.id = :bookingId AND f.ticket IS NULL
        ORDER BY f.id DESC
    """)
    List<TicketFile> findBookingFace(@Param("bookingId") Long bookingId);

    /** Mặt vé riêng của 1 vé (ticket != null). Tối đa 1 dòng. */
    @Query("""
        SELECT f FROM TicketFile f
        WHERE f.ticket.id = :ticketId
        ORDER BY f.id DESC
    """)
    List<TicketFile> findTicketFace(@Param("ticketId") Long ticketId);

    default Optional<TicketFile> findFirstBookingFace(Long bookingId) {
        var list = findBookingFace(bookingId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    default Optional<TicketFile> findFirstTicketFace(Long ticketId) {
        var list = findTicketFace(ticketId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }
}