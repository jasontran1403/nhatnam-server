package com.nhatnam.server.tools.vmb.service;

import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.vmb.dto.VmbDtos.*;
import com.nhatnam.server.tools.vmb.entity.*;
import com.nhatnam.server.tools.vmb.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Nghiệp vụ tab Thông tin khách.
 *
 * "Khách lẻ" là một Company mặc định seed sẵn ở khởi động (xem VmbSeedRunner).
 * Passenger luôn có 1 company — nếu người dùng không chọn, mặc định "Khách lẻ".
 *
 * ── Giấy tờ (2026-09-15) ────────────────────────────────
 * Số CCCD / hộ chiếu + ngày cấp + ngày hết hạn + file ảnh nay lưu ở
 * {@link PassengerDocument} — mỗi loại 1 dòng riêng biệt vì 2 loại có
 * ngày cấp / hết hạn khác nhau.
 *
 *   PUT    /api/tools/vmb/passengers/{id}/documents/{type}   — upsert (multipart)
 *   DELETE /api/tools/vmb/passengers/{id}/documents/{type}   — xóa hoàn toàn
 *
 * type ∈ {cccd, passport} (case-insensitive; service chuẩn hóa UPPER).
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class PassengerService {

    public static final String DEFAULT_COMPANY_NAME = "Khách lẻ";
    private static final int MAX_CARD_NUMBER_LEN = 40;

    private static final Set<String> DOC_TYPES = Set.of(PassengerDocument.TYPE_CCCD, PassengerDocument.TYPE_PASSPORT);
    private static final Set<String> GENDERS   = Set.of("MALE", "FEMALE", "OTHER");

    private final PassengerRepository         passengerRepo;
    private final PassengerDocumentRepository docRepo;
    private final CompanyRepository           companyRepo;
    private final MembershipCardRepository    cardRepo;
    private final VmbStorageService           storage;

    // ── COMPANIES ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Company> listCompanies() {
        return companyRepo.findAllByOrderByNameAsc();
    }

    @Transactional
    public Company findOrCreateCompany(String name) {
        String key = (name == null || name.isBlank()) ? DEFAULT_COMPANY_NAME : name.trim();
        return companyRepo.findByNameIgnoreCase(key).orElseGet(() ->
                companyRepo.save(Company.builder()
                        .name(key)
                        .createdAt(System.currentTimeMillis())
                        .build()));
    }

    @Transactional
    public Company createCompany(String name) {
        if (name == null || name.isBlank()) throw new ToolsException("Tên công ty không được để trống.");
        String trimmed = name.trim();
        companyRepo.findByNameIgnoreCase(trimmed).ifPresent(c -> {
            throw new ToolsException("Công ty này đã tồn tại.");
        });
        return companyRepo.save(Company.builder()
                .name(trimmed).createdAt(System.currentTimeMillis()).build());
    }

    @Transactional
    public Company renameCompany(Long id, String name) {
        if (name == null || name.isBlank()) throw new ToolsException("Tên công ty không được để trống.");
        var c = companyRepo.findById(id).orElseThrow(() -> new ToolsException("Không tìm thấy công ty."));
        if (DEFAULT_COMPANY_NAME.equals(c.getName()))
            throw new ToolsException("Không thể đổi tên công ty mặc định.");
        c.setName(name.trim());
        return c;
    }

    @Transactional
    public void deleteCompany(Long id) {
        var c = companyRepo.findById(id).orElseThrow(() -> new ToolsException("Không tìm thấy công ty."));
        if (DEFAULT_COMPANY_NAME.equals(c.getName()))
            throw new ToolsException("Không thể xóa công ty mặc định.");
        long count = passengerRepo.countByCompanyId(id);
        if (count > 0) {
            throw new ToolsException("Công ty vẫn còn hành khách — chuyển họ sang công ty khác trước khi xóa.");
        }
        companyRepo.delete(c);
    }

    // ── PASSENGERS ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<Passenger> search(String q, Long companyId, int page, int size) {
        return passengerRepo.search(
                (q != null && !q.isBlank()) ? q.trim() : null,
                companyId,
                PageRequest.of(page, Math.min(Math.max(size, 1), 200)));
    }

    @Transactional(readOnly = true)
    public Passenger findFull(Long id) {
        var p = passengerRepo.findFullById(id);
        if (p == null) throw new ToolsException("Không tìm thấy hành khách.");
        return p;
    }

    @Transactional
    public Passenger create(SavePassengerRequest req) {
        validatePassenger(req);
        Company c = resolveCompany(req);
        long now = System.currentTimeMillis();
        return passengerRepo.save(Passenger.builder()
                .company(c)
                .fullName(req.getFullName().trim())
                .dob(nz(req.getDob()))
                .gender(normalizeGender(req.getGender()))
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Transactional
    public Passenger update(Long id, SavePassengerRequest req) {
        validatePassenger(req);
        var p = passengerRepo.findById(id).orElseThrow(() -> new ToolsException("Không tìm thấy hành khách."));
        p.setCompany(resolveCompany(req));
        p.setFullName(req.getFullName().trim());
        p.setDob(nz(req.getDob()));
        p.setGender(normalizeGender(req.getGender()));
        p.setUpdatedAt(System.currentTimeMillis());
        return p;
    }

    @Transactional
    public void delete(Long id) {
        var p = passengerRepo.findFullById(id);
        if (p == null) return;
        // Dọn file trên đĩa
        for (PassengerDocument d : p.getDocuments()) {
            if (d.getStoredFile() != null) storage.delete(d.getStoredFile());
        }
        passengerRepo.delete(p);
    }

    // ── DOCUMENTS ───────────────────────────────────────────────

    /**
     * Upsert document theo type (CCCD | PASSPORT). File có thể vắng (chỉ update
     * các field text) hoặc đi kèm để thay ảnh cũ.
     */
    @Transactional
    public PassengerDocument upsertDocument(Long passengerId, String type,
                                            SavePassengerDocumentRequest req,
                                            MultipartFile file) {
        String t = normalizeType(type);
        var p = passengerRepo.findById(passengerId)
                .orElseThrow(() -> new ToolsException("Không tìm thấy hành khách."));

        var existing = docRepo.findByPassengerIdAndType(passengerId, t).orElse(null);
        long now = System.currentTimeMillis();
        boolean isCreate = (existing == null);

        PassengerDocument d = existing != null ? existing : PassengerDocument.builder()
                .passenger(p)
                .type(t)
                .createdAt(now)
                .build();

        d.setDocNumber(nz(req.getDocNumber()));
        if (PassengerDocument.TYPE_PASSPORT.equals(t)) {
            d.setNationality(normalizeNationality(req.getNationality()));
        } else {
            d.setNationality(null);  // CCCD không có nationality
        }
        d.setIssueDate(nz(req.getIssueDate()));
        d.setExpiryDate(nz(req.getExpiryDate()));
        d.setUpdatedAt(now);

        if (file != null && !file.isEmpty()) {
            if (d.getStoredFile() != null) storage.delete(d.getStoredFile());
            var s = storage.store(file);
            d.setStoredFile(s.storedName());
            d.setOriginalFile(s.originalName());
        }

        if (isCreate) {
            p.getDocuments().add(d);
            return docRepo.save(d);
        }
        return d;
    }

    @Transactional
    public void deleteDocument(Long passengerId, String type) {
        String t = normalizeType(type);
        var d = docRepo.findByPassengerIdAndType(passengerId, t).orElse(null);
        if (d == null) return;
        if (d.getStoredFile() != null) storage.delete(d.getStoredFile());
        // Xóa qua passenger.documents để giữ orphanRemoval consistency
        var p = passengerRepo.findFullById(passengerId);
        if (p != null) {
            p.getDocuments().removeIf(doc -> doc.getId().equals(d.getId()));
        } else {
            docRepo.delete(d);
        }
    }

    // ── MEMBERSHIP CARDS ────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<MembershipCard> listCards(Long passengerId) {
        return cardRepo.findByPassengerIdOrderByIdAsc(passengerId);
    }

    @Transactional
    public MembershipCard createCard(Long passengerId, MembershipCardIO in) {
        validateCard(in);
        var p = passengerRepo.findById(passengerId)
                .orElseThrow(() -> new ToolsException("Không tìm thấy hành khách."));
        long now = System.currentTimeMillis();
        return cardRepo.save(MembershipCard.builder()
                .passenger(p)
                .passengerName(in.getPassengerName().trim())
                .cardNumber(in.getCardNumber().trim())
                .airlineCode(in.getAirlineCode().trim())
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Transactional
    public MembershipCard updateCard(Long cardId, MembershipCardIO in) {
        validateCard(in);
        var c = cardRepo.findById(cardId).orElseThrow(() -> new ToolsException("Không tìm thấy thẻ."));
        c.setPassengerName(in.getPassengerName().trim());
        c.setCardNumber(in.getCardNumber().trim());
        c.setAirlineCode(in.getAirlineCode().trim());
        c.setUpdatedAt(System.currentTimeMillis());
        return c;
    }

    @Transactional
    public void deleteCard(Long cardId) {
        cardRepo.deleteById(cardId);
    }

    // ═══════════════════════════════════════════════════════════════

    private Company resolveCompany(SavePassengerRequest req) {
        if (req.getCompanyId() != null) {
            return companyRepo.findById(req.getCompanyId())
                    .orElseThrow(() -> new ToolsException("Công ty không tồn tại."));
        }
        return findOrCreateCompany(req.getNewCompanyName());
    }

    private static void validatePassenger(SavePassengerRequest req) {
        if (req == null) throw new ToolsException("Thiếu dữ liệu.");
        if (req.getFullName() == null || req.getFullName().isBlank())
            throw new ToolsException("Tên hành khách không được để trống.");
    }

    private static void validateCard(MembershipCardIO c) {
        if (c == null) throw new ToolsException("Thiếu dữ liệu.");
        if (c.getPassengerName() == null || c.getPassengerName().isBlank())
            throw new ToolsException("Tên hành khách không được để trống.");
        if (c.getCardNumber() == null || c.getCardNumber().isBlank())
            throw new ToolsException("Số thẻ không được để trống.");
        if (c.getAirlineCode() == null || c.getAirlineCode().isBlank())
            throw new ToolsException("Hãng bay không được để trống.");
        if (c.getCardNumber().length() > MAX_CARD_NUMBER_LEN)
            throw new ToolsException("Số thẻ quá dài.");
    }

    private static String normalizeType(String type) {
        if (type == null) throw new ToolsException("Thiếu loại giấy tờ.");
        String t = type.trim().toUpperCase(Locale.ROOT);
        if (!DOC_TYPES.contains(t))
            throw new ToolsException("Loại giấy tờ không hợp lệ (CCCD | PASSPORT).");
        return t;
    }

    private static String normalizeGender(String g) {
        if (g == null || g.isBlank()) return null;
        String t = g.trim().toUpperCase(Locale.ROOT);
        return GENDERS.contains(t) ? t : null;
    }

    /**
     * Nationality: ISO-3 uppercase (VNM, JPN, USA...). Chấp nhận null/rỗng.
     * Nếu người dùng gõ 2 hoặc 3 ký tự thường, tự UPPER; nhiều ký tự hơn thì
     * cắt còn 3. Không kiểm danh sách ISO thật — người dùng biết mã cần gõ.
     */
    private static String normalizeNationality(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim().toUpperCase(Locale.ROOT);
        return t.length() > 3 ? t.substring(0, 3) : t;
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }
}