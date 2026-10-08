package com.nhatnam.server.tools.vmb.repository;

import com.nhatnam.server.tools.vmb.entity.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repository cho Booking.
 *
 * ── search(...) ─────────────────────────────────────────────
 * LIKE q trên bookingCode / ticketNumber / passengerName / routeStr.
 * Kèm filter theo saleDate range (từ 1 ngày → 1 khoảng tùy chọn ở FE).
 * Bỏ EntityGraph — dùng @BatchSize để lazy load segments/tickets.
 *
 * ── searchIdsForTotals(...) ─────────────────────────────────
 * Trả VỀ ĐẦY ĐỦ Booking (không phân trang) để service tính totals — cần
 * load segments/tickets bằng batch, khác search() ở chỗ không có Pageable.
 * Có thể tốn thời gian với dataset lớn (chấp nhận vì totals không tính
 * được bằng SUM SQL do prices là String).
 */
public interface BookingRepository extends JpaRepository<Booking, Long> {

    @Query(
        value = """
            SELECT DISTINCT b FROM Booking b
            LEFT JOIN b.tickets t
            WHERE (:q IS NULL OR :q = ''
                OR LOWER(b.bookingCode)     LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(t.ticketNumber)    LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(t.passengerName)   LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(b.routeStr)        LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:fromSale IS NULL OR b.saleDate >= :fromSale)
              AND (:toSale   IS NULL OR b.saleDate <= :toSale)
            ORDER BY b.createdAt DESC, b.id DESC
        """,
        countQuery = """
            SELECT COUNT(DISTINCT b) FROM Booking b
            LEFT JOIN b.tickets t
            WHERE (:q IS NULL OR :q = ''
                OR LOWER(b.bookingCode)     LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(t.ticketNumber)    LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(t.passengerName)   LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(b.routeStr)        LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:fromSale IS NULL OR b.saleDate >= :fromSale)
              AND (:toSale   IS NULL OR b.saleDate <= :toSale)
        """
    )
    Page<Booking> search(@Param("q") String q,
                         @Param("fromSale") Long fromSale,
                         @Param("toSale")   Long toSale,
                         Pageable pageable);

    /** List toàn bộ booking match filter (không phân trang) để tính totals. */
    @Query("""
        SELECT DISTINCT b FROM Booking b
        LEFT JOIN b.tickets t
        WHERE (:q IS NULL OR :q = ''
            OR LOWER(b.bookingCode)     LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(t.ticketNumber)    LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(t.passengerName)   LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(b.routeStr)        LIKE LOWER(CONCAT('%', :q, '%')))
          AND (:fromSale IS NULL OR b.saleDate >= :fromSale)
          AND (:toSale   IS NULL OR b.saleDate <= :toSale)
    """)
    List<Booking> searchAll(@Param("q") String q,
                            @Param("fromSale") Long fromSale,
                            @Param("toSale")   Long toSale);

    default Booking findFullById(Long id) {
        return findById(id).orElse(null);
    }
}
