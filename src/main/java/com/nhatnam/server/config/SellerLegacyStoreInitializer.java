package com.nhatnam.server.config;

import com.nhatnam.server.entity.User;
import com.nhatnam.server.entity.pos.PosStore;
import com.nhatnam.server.entity.pos.SellerStore;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.repository.pos.PosStoreRepository;
import com.nhatnam.server.repository.pos.SellerStoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seed mapping cho 2 SELLER cũ (id=1, id=2) → store legacy.
 * Chạy sau UserDataInitializer (@Order(1)) và PosStoreDataInitializer.
 *
 * Logic:
 *  - seller1 (id=1) và seller2 (id=2) → gắn với store đầu tiên (legacy store).
 *  - isLegacy = true → họ dùng chung kho với các SELLER cũ.
 *  - SELLER mới được tạo sau: AdminController sẽ tự tạo store mới + gắn isLegacy=false.
 */
@Log4j2
@Component
@Order(200)
@RequiredArgsConstructor
public class SellerLegacyStoreInitializer implements CommandLineRunner {

    private final SellerStoreRepository sellerStoreRepository;
    private final UserRepository        userRepository;
    private final PosStoreRepository    posStoreRepository;

    // ID của 2 seller cũ — khớp với UserDataInitializer
    private static final long LEGACY_SELLER1_ID = 1L;
    private static final long LEGACY_SELLER2_ID = 2L;

    // Dùng store đầu tiên làm "legacy store" (hoặc tạo store riêng tên "Kho Cũ")
    private static final long LEGACY_STORE_ID   = 1L;

    @Override
    public void run(String... args) {
        seed(LEGACY_SELLER1_ID, LEGACY_STORE_ID);
        seed(LEGACY_SELLER2_ID, LEGACY_STORE_ID);
    }

    private void seed(Long sellerId, Long storeId) {
        if (sellerStoreRepository.existsBySellerId(sellerId)) {
            return;
        }

        User seller = userRepository.findById(sellerId).orElse(null);
        if (seller == null) {
            log.warn("[SellerStore] Seller {} not found, skip", sellerId);
            return;
        }

        PosStore store = posStoreRepository.findById(storeId).orElse(null);
        if (store == null) {
            log.warn("[SellerStore] Store {} not found, skip", storeId);
            return;
        }

        sellerStoreRepository.save(
            SellerStore.builder()
                .seller(seller)
                .store(store)
                .isLegacy(true)
                .build()
        );
        log.info("[SellerStore] Gán seller {} → store {} (legacy)", sellerId, storeId);
    }
}
