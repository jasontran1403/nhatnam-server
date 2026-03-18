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
    public static String normalizePhone(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("\\s+", "");
        if (s.startsWith("+")) s = s.substring(1);
        if (s.startsWith("84") && s.length() == 11) s = "0" + s.substring(2);
        if (!s.startsWith("0") && s.length() == 9)  s = "0" + s;
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

    public PosCustomer createOrUpdate(String rawPhone, String name) {
        String normalized = normalizePhone(rawPhone);
        return repo.findByPhone(normalized)
                .map(c -> { c.setName(name); return repo.save(c); })
                .orElseGet(() -> repo.save(
                        PosCustomer.builder()
                                .phone(normalized)
                                .name(name.trim())
                                .build()));
    }
}