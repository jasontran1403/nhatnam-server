package com.nhatnam.server.config;

import com.nhatnam.server.entity.SellerWarehouse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.entity.Warehouse;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.SellerWarehouseRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Khởi tạo 2 kho nguyên liệu và gán SELLER vào đúng kho.
 *
 * Kho 1 (Kho cũ):  seller1 (id=1), seller2 (id=2) — KHÔNG gán SellerWarehouse,
 *                  họ vẫn dùng ingredient.stockQuantity (legacy).
 *
 * Kho 2 (Kho mới): các SELLER có id > LEGACY_SELLER_MAX_ID → gán vào Kho mới,
 *                  tồn kho lấy từ WarehouseIngredientStock (bắt đầu từ 0).
 *
 * Logic:
 *  - seller1, seller2 KHÔNG có SellerWarehouse mapping → InventoryBatchService
 *    tự nhận diện là legacy và dùng ingredient.stockQuantity.
 *  - seller3+ CÓ SellerWarehouse mapping → warehouseId=2 (Kho mới).
 */
@Log4j2
@Component
@Order(201)  // chạy sau SellerLegacyStoreInitializer
@RequiredArgsConstructor
public class WarehouseDataInitializer implements CommandLineRunner {

    private static final long LEGACY_SELLER1_ID = 1L;
    private static final long LEGACY_SELLER2_ID = 2L;

    private final WarehouseRepository        warehouseRepository;
    private final SellerWarehouseRepository  sellerWarehouseRepository;
    private final UserRepository             userRepository;

    @Override
    public void run(String... args) {
        // 1. Tạo Kho cũ (id sẽ là 1 nếu chưa có)
        Warehouse legacyWarehouse = ensureWarehouse(1L, "Kho cũ", "Kho nguyên liệu gốc (seller1, seller2)");

        // 2. Tạo Kho mới (id sẽ là 2)
        Warehouse newWarehouse    = ensureWarehouse(2L, "Kho mới", "Kho nguyên liệu mới (seller3 trở đi)");

        // 3. Gán tất cả SELLER có id > 2 vào Kho mới (nếu chưa gán)
        List<User> sellers = userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.SELLER
                        && u.getId() != LEGACY_SELLER1_ID
                        && u.getId() != LEGACY_SELLER2_ID)
                .toList();

        for (User seller : sellers) {
            if (!sellerWarehouseRepository.existsBySellerId(seller.getId())) {
                sellerWarehouseRepository.save(
                        SellerWarehouse.builder()
                                .seller(seller)
                                .warehouse(newWarehouse)
                                .build());
                log.info("[Warehouse] Gán seller {} ({}) → Kho mới (id={})",
                        seller.getId(), seller.getUsername(), newWarehouse.getId());
            }
        }
    }

    private Warehouse ensureWarehouse(Long expectedId, String name, String description) {
        return warehouseRepository.findById(expectedId).orElseGet(() -> {
            Warehouse w = warehouseRepository.save(
                    Warehouse.builder()
                            .name(name)
                            .description(description)
                            .createdAt(System.currentTimeMillis())
                            .build());
            log.info("[Warehouse] Tạo {} id={}", name, w.getId());
            return w;
        });
    }
}
