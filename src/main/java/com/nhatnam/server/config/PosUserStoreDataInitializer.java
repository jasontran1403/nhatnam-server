// src/main/java/com/nhatnam/server/config/PosUserStoreDataInitializer.java

package com.nhatnam.server.config;

import com.nhatnam.server.entity.pos.PosStore;
import com.nhatnam.server.entity.pos.PosUserStore;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.repository.pos.PosStoreRepository;
import com.nhatnam.server.repository.pos.PosUserStoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seed mapping:
 *   user id=3  (447hvb, POS) → store id=6  (447HVB)
 *   user id=6  (test,   POS) → store id=6  (447HVB)  ← nếu muốn, tùy chỉnh
 *
 * Chỉ insert nếu chưa có. Safe to run nhiều lần.
 */
@Log4j2
@Component
@Order(100)
@RequiredArgsConstructor
public class PosUserStoreDataInitializer implements CommandLineRunner {

    private final PosUserStoreRepository posUserStoreRepository;
    private final UserRepository          userRepository;
    private final PosStoreRepository      posStoreRepository;

    @Override
    public void run(String... args) {
        // user 3 (447hvb) → store 6 (447HVB)
        seed(3L, 6L);

        seed(9L, 5L);
    }

    private void seed(Long userId, Long storeId) {
        if (posUserStoreRepository.existsByUserId(userId)) {
            log.info("[PosUserStore] User {} already mapped, skip", userId);
            return;
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("[PosUserStore] User {} not found, skip", userId);
            return;
        }

        PosStore store = posStoreRepository.findById(storeId).orElse(null);
        if (store == null) {
            log.warn("[PosUserStore] Store {} not found, skip", storeId);
            return;
        }

        posUserStoreRepository.save(
                PosUserStore.builder().user(user).store(store).build()
        );
        log.info("[PosUserStore] Mapped user={} ({}) → store={} ({})",
                userId, user.getUsername(), storeId, store.getName());
    }
}