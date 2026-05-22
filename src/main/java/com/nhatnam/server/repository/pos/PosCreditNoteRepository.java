package com.nhatnam.server.repository.pos;

import com.nhatnam.server.entity.pos.PosCreditNote;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
