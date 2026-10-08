package com.nhatnam.server.tools.vmb.repository;

import com.nhatnam.server.tools.vmb.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    /**
     * Lấy invoices có liên kết tới ticketId cho trước (qua bảng join
     * vmb_invoice_ticket). Không bao gồm invoice "chung cả booking" (empty
     * ticket set) — caller nếu cần cũng phải merge riêng.
     */
    @Query("""
        SELECT i FROM Invoice i JOIN i.tickets t
        WHERE t.id = :ticketId
        ORDER BY i.id ASC
    """)
    List<Invoice> findByTicketId(@Param("ticketId") Long ticketId);
}