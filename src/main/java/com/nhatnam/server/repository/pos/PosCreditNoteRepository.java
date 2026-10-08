package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosCreditNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosCreditNoteRepository
        extends JpaRepository<PosCreditNote, Long> {
    List<PosCreditNote> findByCustomerIdAndStoreIdAndStatusIn(
            Long customerId, Long storeId,
            List<PosCreditNote.CreditNoteStatus> statuses);
    List<PosCreditNote> findByStoreIdAndExpiredAtBefore(
            Long storeId, Long now);
    List<PosCreditNote> findByExpiredAtBeforeAndStatusIn(
            Long expiredAt, List<PosCreditNote.CreditNoteStatus> statuses);

    /**
     * Số dư credit khả dụng của mọi khách trong 1 store — trả [customerId, sum].
     * Truyền statuses vào (ACTIVE, PARTIALLY_USED) để khớp "Credit khả dụng"
     * ở popup Khách hàng thân thiết.
     */
    @Query("""
        SELECT c.customer.id, COALESCE(SUM(c.remainingAmount), 0)
        FROM PosCreditNote c
        WHERE c.storeId = :storeId
          AND c.status IN :statuses
        GROUP BY c.customer.id
    """)
    List<Object[]> sumAvailableCreditByStore(
            @Param("storeId")  Long storeId,
            @Param("statuses") List<PosCreditNote.CreditNoteStatus> statuses);
}