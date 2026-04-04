package com.nhatnam.server.repository;

import com.nhatnam.server.entity.InventoryBatch;
import com.nhatnam.server.enumtype.InventoryAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InventoryBatchRepository extends JpaRepository<InventoryBatch, Long> {

    /** Tất cả batch, sort createdAt DESC */
    Page<InventoryBatch> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Lọc theo action */
    Page<InventoryBatch> findByActionOrderByCreatedAtDesc(InventoryAction action, Pageable pageable);

    /** Detail kèm logs + ingredient (tránh N+1) */
    @Query("""
        SELECT DISTINCT b FROM InventoryBatch b
        LEFT JOIN FETCH b.logs l
        LEFT JOIN FETCH l.ingredient
        LEFT JOIN FETCH b.createdBy
        WHERE b.id = :id
    """)
    Optional<InventoryBatch> findByIdWithLogs(@Param("id") Long id);
}