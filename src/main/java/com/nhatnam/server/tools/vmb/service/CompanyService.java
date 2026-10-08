package com.nhatnam.server.tools.vmb.service;

import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.vmb.dto.VmbDtos.*;
import com.nhatnam.server.tools.vmb.entity.Company;
import com.nhatnam.server.tools.vmb.repository.CompanyRepository;
import com.nhatnam.server.tools.vmb.repository.PassengerRepository;
import com.nhatnam.server.tools.vmb.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD công ty + tạo chi nhánh.
 *
 * ── Chi nhánh (Phase G) ────────────────────────────────
 * "Chi nhánh" chỉ là 1 hàng Company có {@code parentId} trỏ về công ty mẹ.
 * Không cho phép chi nhánh của chi nhánh (parentId của mẹ phải null).
 *
 * Khi tạo chi nhánh:
 *   - Tên mặc định = "{tên mẹ} - Chi nhánh {N+1}" nhưng user có thể sửa
 *   - MST mặc định = "{mst mẹ}-{N+1:001}" nhưng user có thể sửa
 *   - Địa chỉ / SĐT / email: copy từ mẹ làm placeholder (client), server
 *     nhận giá trị FE gửi lên — nếu để trống, giữ null (server không tự copy)
 *
 * ── Xóa ────────────────────────────────────────────────
 * Xóa công ty mẹ → CASCADE xóa tất cả chi nhánh (thao tác thủ công vì
 * không dùng JPA cascade — an toàn hơn). Chặn nếu bất kỳ ai (mẹ hoặc
 * chi nhánh) đang được passenger/ticket tham chiếu.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class CompanyService {

    private final CompanyRepository repo;
    private final PassengerRepository passengerRepo;
    private final TicketRepository ticketRepo;

    // ── List ───────────────────────────────────────────────

    /** Trả về danh sách phẳng có kèm parentId — FE tự dựng cây nếu cần. */
    @Transactional(readOnly = true)
    public List<CompanyIO> listAll() {
        return repo.findAllByOrderByNameAsc().stream().map(CompanyService::view).toList();
    }

    // ── Create ─────────────────────────────────────────────

    /** Tạo công ty gốc (parentId = null). */
    @Transactional
    public CompanyIO create(SaveCompanyRequest req) {
        return doSave(req, /* parent */ null);
    }

    /**
     * Tạo chi nhánh của công ty {@code parentId}. parent phải là công ty GỐC
     * (không cho phép chi nhánh của chi nhánh).
     */
    @Transactional
    public CompanyIO createBranch(Long parentId, SaveCompanyRequest req) {
        Company parent = repo.findById(parentId)
                .orElseThrow(() -> new ToolsException("Không tìm thấy công ty mẹ."));
        if (parent.getParentId() != null) {
            throw new ToolsException("Không thể tạo chi nhánh của chi nhánh. Vui lòng chọn công ty gốc.");
        }
        return doSave(req, parent);
    }

    private CompanyIO doSave(SaveCompanyRequest req, Company parent) {
        String name = trim(req.getName());
        if (name == null) throw new ToolsException("Tên công ty không được để trống.");
        if (repo.existsByNameIgnoreCase(name)) {
            throw new ToolsException("Tên công ty đã tồn tại: " + name);
        }
        String taxId = trim(req.getTaxId());
        if (taxId != null && repo.existsByTaxId(taxId)) {
            throw new ToolsException("Mã số thuế đã tồn tại: " + taxId);
        }
        long now = System.currentTimeMillis();
        Company c = Company.builder()
                .name(name)
                .shortName(trim(req.getShortName()))
                .address(trim(req.getAddress()))
                .taxId(taxId)
                .phone(trim(req.getPhone()))
                .email(trim(req.getEmail()))
                .parentId(parent != null ? parent.getId() : null)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return view(repo.save(c));
    }

    // ── Update ─────────────────────────────────────────────

    @Transactional
    public CompanyIO update(Long id, SaveCompanyRequest req) {
        Company c = repo.findById(id).orElseThrow(() -> new ToolsException("Không tìm thấy công ty."));

        String name = trim(req.getName());
        if (name == null) throw new ToolsException("Tên công ty không được để trống.");
        // Nếu đổi tên, kiểm trùng với bản ghi khác
        if (!name.equalsIgnoreCase(c.getName()) && repo.existsByNameIgnoreCase(name)) {
            throw new ToolsException("Tên công ty đã tồn tại: " + name);
        }
        String taxId = trim(req.getTaxId());
        if (taxId != null && !taxId.equals(c.getTaxId()) && repo.existsByTaxId(taxId)) {
            throw new ToolsException("Mã số thuế đã tồn tại: " + taxId);
        }

        c.setName(name);
        c.setShortName(trim(req.getShortName()));
        c.setAddress(trim(req.getAddress()));
        c.setTaxId(taxId);
        c.setPhone(trim(req.getPhone()));
        c.setEmail(trim(req.getEmail()));
        c.setUpdatedAt(System.currentTimeMillis());
        return view(repo.save(c));
    }

    // ── Delete ─────────────────────────────────────────────

    @Transactional
    public void delete(Long id) {
        Company c = repo.findById(id).orElseThrow(() -> new ToolsException("Không tìm thấy công ty."));

        // Nếu công ty gốc, xóa tất cả chi nhánh trước
        List<Company> branches = c.getParentId() == null
                ? repo.findByParentIdOrderByCreatedAtAsc(c.getId())
                : List.of();

        // Chặn nếu bất kỳ ai đang được tham chiếu bởi passenger/ticket
        assertNotReferenced(c);
        for (Company br : branches) assertNotReferenced(br);

        for (Company br : branches) repo.delete(br);
        repo.delete(c);
    }

    private void assertNotReferenced(Company c) {
        long paxCount = passengerRepo.countByCompanyId(c.getId());
        long tkCount  = ticketRepo.countByCompanyId(c.getId());
        if (paxCount > 0 || tkCount > 0) {
            throw new ToolsException(
                "Không thể xóa \"" + c.getName() + "\" vì còn " + paxCount + " hành khách + "
                + tkCount + " vé đang tham chiếu. Chuyển hoặc xóa các bản ghi đó trước.");
        }
    }

    // ── Utility ────────────────────────────────────────────

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public static CompanyIO view(Company c) {
        return CompanyIO.builder()
                .id(c.getId())
                .name(c.getName())
                .shortName(c.getShortName())
                .address(c.getAddress())
                .taxId(c.getTaxId())
                .phone(c.getPhone())
                .email(c.getEmail())
                .parentId(c.getParentId())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
