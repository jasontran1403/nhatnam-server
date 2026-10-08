package com.nhatnam.server.tools.vmb.repository;

import com.nhatnam.server.tools.vmb.entity.BookingPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingPaymentRepository extends JpaRepository<BookingPayment, Long> {

    List<BookingPayment> findByBookingIdOrderByCreatedAtAsc(Long bookingId);

    /** Đếm số record khác đang dùng cùng ảnh — dùng khi xóa 1 payment. */
    long countByStoredName(String storedName);
}
