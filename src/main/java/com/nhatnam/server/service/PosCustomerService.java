// service/PosCustomerService.java
package com.nhatnam.server.service;

import com.nhatnam.server.entity.pos.PosCustomer;
import com.nhatnam.server.repository.pos.PosCustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PosCustomerService {

    private final PosCustomerRepository repo;

    // ── Chuẩn hoá SĐT → 0xxxxxxxxx ──────────────────────────────
    // Hỗ trợ: +84913643618, 84913643618, 913643618, 0913643618
    public static String normalizePhone(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[\\s\\-]", "");   // xóa space + dash
        if (s.startsWith("+84")) s = "0" + s.substring(3);
        else if (s.startsWith("84") && s.length() == 11) s = "0" + s.substring(2);
        else if (!s.startsWith("0") && s.length() == 9)  s = "0" + s;
        return s;
    }

    public List<PosCustomer> search(String rawPhone) {
        String normalized = normalizePhone(rawPhone);
        if (normalized == null || normalized.isBlank()) return List.of();
        return repo.findByPhoneContaining(normalized);
    }

    public Optional<PosCustomer> findByPhone(String rawPhone) {
        String normalized = normalizePhone(rawPhone);
        if (normalized == null) return Optional.empty();
        return repo.findByPhone(normalized);
    }

    /**
     * Tạo mới hoặc cập nhật khách hàng.
     * - Nếu đã tồn tại theo phone → cập nhật name + các fields nếu có giá trị mới
     * - Nếu chưa có → tạo mới
     * - referredByPhone: tìm khách hàng theo SĐT → lấy id, name, phone snapshot
     *   Nếu không tìm thấy → bỏ qua, không báo lỗi
     *   Nếu đã có referrer → không ghi đè
     */
    public PosCustomer createOrUpdate(
            String rawPhone,
            String name,
            long   storeId,
            String dateOfBirth,       // nullable
            String deliveryAddress,   // nullable
            String rawReferredByPhone // nullable
    ) {
        String phone = normalizePhone(rawPhone);

        // Resolve người giới thiệu
        Long   referredById    = null;
        String referredByName  = null;
        String referredByPhone = null;

        if (rawReferredByPhone != null && !rawReferredByPhone.isBlank()) {
            String refPhone = normalizePhone(rawReferredByPhone);
            // Không thể tự giới thiệu chính mình
            if (!refPhone.equals(phone)) {
                Optional<PosCustomer> referrer = repo.findByPhone(refPhone);
                if (referrer.isPresent()) {
                    referredById    = referrer.get().getId();
                    referredByName  = referrer.get().getName();
                    referredByPhone = referrer.get().getPhone();
                }
                // Không tìm thấy → bỏ qua, không set
            }
        }

        final Long   finalRefId    = referredById;
        final String finalRefName  = referredByName;
        final String finalRefPhone = referredByPhone;

        return repo.findByPhone(phone)
                .map(c -> {
                    // Cập nhật name
                    c.setName(name.trim());

                    // Cập nhật optional fields nếu có giá trị
                    if (dateOfBirth != null && !dateOfBirth.isBlank())
                        c.setDateOfBirth(dateOfBirth.trim());
                    if (deliveryAddress != null && !deliveryAddress.isBlank())
                        c.setDeliveryAddress(deliveryAddress.trim());

                    // Chỉ set referrer nếu chưa có (không cho đổi người giới thiệu)
                    if (c.getReferredByCustomerId() == null && finalRefId != null) {
                        c.setReferredByCustomerId(finalRefId);
                        c.setReferredByName(finalRefName);
                        c.setReferredByPhone(finalRefPhone);
                    }

                    return repo.save(c);
                })
                .orElseGet(() -> repo.save(
                        PosCustomer.builder()
                                .phone(phone)
                                .storeId(storeId)
                                .name(name.trim())
                                .dateOfBirth(
                                        dateOfBirth != null && !dateOfBirth.isBlank()
                                                ? dateOfBirth.trim() : null)
                                .deliveryAddress(
                                        deliveryAddress != null && !deliveryAddress.isBlank()
                                                ? deliveryAddress.trim() : null)
                                .referredByCustomerId(finalRefId)
                                .referredByName(finalRefName)
                                .referredByPhone(finalRefPhone)
                                .build()));
    }

    // ── Overload giữ backward-compat (gọi từ createOrder khi tự tạo khách) ──
    public PosCustomer createOrUpdate(String rawPhone, String name, long storeId) {
        return createOrUpdate(rawPhone, name, storeId, null, null, null);
    }
}