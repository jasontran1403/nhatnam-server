package com.nhatnam.server.config;

import com.nhatnam.server.entity.Ingredient;
import com.nhatnam.server.entity.InventoryCostLot;
import com.nhatnam.server.entity.Warehouse;
import com.nhatnam.server.entity.WarehouseIngredientStock;
import com.nhatnam.server.repository.IngredientRepository;
import com.nhatnam.server.repository.WarehouseIngredientStockRepository;
import com.nhatnam.server.repository.WarehouseRepository;
import com.nhatnam.server.service.serviceimpl.InventoryCostLotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * BACKFILL tồn kho cũ → lô giá vốn (FIFO).
 *
 * Với MỌI nguyên liệu đang có tồn kho nhưng chưa được phủ bởi lô nào:
 *   → tạo 1 lô OPENING duy nhất:
 *        unitCost   = 0
 *        expiryDate = hôm nay + 1 năm
 *        createdAt  = 0   (FIFO xuất trước tiên)
 *
 * Phạm vi:
 *   - Kho chung (legacy, warehouse = null) → ingredient.stockQuantity
 *   - Từng kho (warehouse != null)         → WarehouseIngredientStock.stockQuantity
 *
 * IDEMPOTENT: chỉ tạo phần THIẾU (tồn kho − tổng lô còn hàng). Chạy lại nhiều lần
 * không sinh trùng lô. Có thể tắt bằng: app.cost-lot.backfill.enabled=false
 */
@Log4j2
@Component
@Order(300) // chạy sau WarehouseDataInitializer (201)
@RequiredArgsConstructor
public class InventoryCostLotBackfillRunner implements CommandLineRunner {

    private final IngredientRepository               ingredientRepository;
    private final WarehouseRepository                warehouseRepository;
    private final WarehouseIngredientStockRepository warehouseStockRepository;
    private final InventoryCostLotService            costLotService;

    @Value("${app.cost-lot.backfill.enabled:true}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(String... args) {
        if (!enabled) {
            log.info("[COST-LOT BACKFILL] Đã tắt (app.cost-lot.backfill.enabled=false)");
            return;
        }

        long now      = System.currentTimeMillis();
        Long expiry   = InventoryCostLotService.defaultExpiry();
        int  created  = 0;

        List<Ingredient> ingredients = ingredientRepository.findByIsActiveTrue();

        // 1) Kho chung (legacy): ingredient.stockQuantity
        for (Ingredient ing : ingredients) {
            BigDecimal stock = ing.getStockQuantity();
            if (stock == null || stock.signum() <= 0) continue;

            InventoryCostLot lot =
                    costLotService.seedOpeningLotIfNeeded(ing, null, stock, now);
            if (lot != null) {
                created++;
                log.info("[COST-LOT BACKFILL] OPENING kho chung — ingredient={} ({}), qty={}, cost=0, hsd={}",
                        ing.getId(), ing.getName(), lot.getRemainingQuantity(), expiry);
            }
        }

        // 2) Từng kho: WarehouseIngredientStock.stockQuantity
        for (Warehouse wh : warehouseRepository.findAll()) {
            for (WarehouseIngredientStock stock : warehouseStockRepository.findByWarehouseId(wh.getId())) {
                BigDecimal qty = stock.getStockQuantity();
                if (qty == null || qty.signum() <= 0) continue;

                Ingredient ing = stock.getIngredient();
                if (ing == null || Boolean.FALSE.equals(ing.getIsActive())) continue;

                InventoryCostLot lot =
                        costLotService.seedOpeningLotIfNeeded(ing, wh, qty, now);
                if (lot != null) {
                    created++;
                    log.info("[COST-LOT BACKFILL] OPENING kho={} — ingredient={} ({}), qty={}, cost=0, hsd={}",
                            wh.getId(), ing.getId(), ing.getName(), lot.getRemainingQuantity(), expiry);
                }
            }
        }

        log.info("[COST-LOT BACKFILL] Hoàn tất — đã tạo {} lô OPENING (giá vốn 0, HSD +1 năm)", created);
    }
}