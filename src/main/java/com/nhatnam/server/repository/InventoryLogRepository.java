package com.nhatnam.server.repository;

import com.nhatnam.server.entity.InventoryLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InventoryLogRepository extends JpaRepository<InventoryLog, Long> {
    Page<InventoryLog> findByIngredientIdOrderByCreatedAtDesc(Long ingredientId, Pageable pageable);
}
