package com.nhatnam.server.tools.vmb.repository;

import com.nhatnam.server.tools.vmb.entity.LookupEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LookupEntryRepository extends JpaRepository<LookupEntry, Long> {

    /**
     * Search theo keyword / loginUsername / details.
     * KHÔNG search theo passwordEnc — không có nghĩa (ciphertext).
     */
    @Query("""
        SELECT l FROM LookupEntry l
        WHERE (:type IS NULL OR :type = '' OR l.type = :type)
          AND (:q IS NULL OR :q = ''
              OR LOWER(l.keyword)       LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(l.loginUsername) LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(l.details)       LIKE LOWER(CONCAT('%', :q, '%')))
        ORDER BY l.type ASC, l.keyword ASC
    """)
    Page<LookupEntry> search(@Param("q") String q,
                             @Param("type") String type,
                             Pageable pageable);
}
