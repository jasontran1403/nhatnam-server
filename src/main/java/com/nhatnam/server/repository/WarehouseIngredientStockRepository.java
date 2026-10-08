package com.nhatnam.server.repository;

import com.nhatnam.server.entity.WarehouseIngredientStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface WarehouseIngredientStockRepository extends JpaRepository<WarehouseIngredientStock, Long> {

    Optional<WarehouseIngredientStock> findByWarehouseIdAndIngredientId(
            Long warehouseId, Long ingredientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM WarehouseIngredientStock s WHERE s.warehouse.id = :wId AND s.ingredient.id = :iId")
    Optional<WarehouseIngredientStock> findByWarehouseIdAndIngredientIdForUpdate(
            @Param("wId") Long warehouseId,
            @Param("iId") Long ingredientId);

    List<WarehouseIngredientStock> findByWarehouseId(Long warehouseId);
}
