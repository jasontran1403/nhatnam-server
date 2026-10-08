package com.nhatnam.server.service;

import com.nhatnam.server.dto.request.CreateSellerRequest;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.entity.pos.PosStore;
import com.nhatnam.server.entity.pos.SellerStore;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.repository.pos.PosStoreRepository;
import com.nhatnam.server.repository.pos.SellerStoreRepository;
import dev.samstevens.totp.secret.SecretGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service xử lý logic cho SELLER và store riêng.
 *
 * Quy tắc:
 *  - SELLER cũ (id=1,2): dùng store cũ (legacy), isLegacy=true.
 *  - SELLER mới tạo từ đây: tạo store riêng, isLegacy=false.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class SellerStoreService {

    private final UserRepository         userRepository;
    private final PosStoreRepository     posStoreRepository;
    private final SellerStoreRepository  sellerStoreRepository;
    private final PasswordEncoder        passwordEncoder;
    private final SecretGenerator        secretGenerator;

    /**
     * Tạo tài khoản SELLER mới + PosStore riêng cho seller đó.
     * Giao dịch atomic: nếu lỗi giữa chừng thì rollback hết.
     */
    @Transactional
    public Map<String, Object> createSellerWithStore(CreateSellerRequest req) {
        // 1. Kiểm tra trùng username/email
        if (userRepository.findByUsername(req.getUsername()).isPresent()) {
            throw new IllegalArgumentException("Username đã tồn tại: " + req.getUsername());
        }
        if (userRepository.findByEmail(req.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email đã tồn tại: " + req.getEmail());
        }

        // 2. Tạo User với role SELLER
        User seller = User.builder()
                .username(req.getUsername())
                .email(req.getEmail())
                .fullName(req.getFullName())
                .phoneNumber(req.getPhoneNumber())
                .password(passwordEncoder.encode(req.getPassword()))
                .role(Role.SELLER)
                .secret(secretGenerator.generate())
                .timeCreate(System.currentTimeMillis())
                .isLockAccount(false)
                .mfaEnabled(false)
                .build();
        seller = userRepository.save(seller);
        log.info("[SellerStore] Tạo SELLER mới: id={}, username={}", seller.getId(), seller.getUsername());

        // 3. Tạo PosStore riêng cho seller này
        PosStore store = PosStore.builder()
                .name(req.getStoreName())
                .address(req.getStoreAddress() != null ? req.getStoreAddress() : "")
                .phone(req.getStorePhone() != null ? req.getStorePhone() : seller.getPhoneNumber())
                .active(true)
                .build();
        store = posStoreRepository.save(store);
        log.info("[SellerStore] Tạo PosStore mới: id={}, name={}", store.getId(), store.getName());

        // 4. Gắn seller → store (isLegacy=false = kho mới, tách biệt)
        SellerStore sellerStore = SellerStore.builder()
                .seller(seller)
                .store(store)
                .isLegacy(false)
                .build();
        sellerStoreRepository.save(sellerStore);
        log.info("[SellerStore] Gán seller {} → store {} (NEW, isLegacy=false)",
                seller.getId(), store.getId());

        // 5. Build response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sellerId",   seller.getId());
        result.put("username",   seller.getUsername());
        result.put("email",      seller.getEmail());
        result.put("fullName",   seller.getFullName());
        result.put("storeId",    store.getId());
        result.put("storeName",  store.getName());
        result.put("isLegacy",   false);
        return result;
    }

    /**
     * Lấy store của seller đang đăng nhập.
     * Ưu tiên SellerStore, fallback về PosUserStore nếu cần.
     */
    public Long resolveStoreIdForSeller(Long sellerId) {
        return sellerStoreRepository.findBySellerId(sellerId)
                .map(ss -> ss.getStore().getId())
                .orElseThrow(() -> new RuntimeException(
                        "SELLER chưa được gán vào store nào. Liên hệ admin."));
    }

    /**
     * Kiểm tra seller có phải tài khoản cũ (legacy) không.
     */
    public boolean isLegacySeller(Long sellerId) {
        return sellerStoreRepository.findBySellerId(sellerId)
                .map(SellerStore::isLegacy)
                .orElse(false);
    }
}
