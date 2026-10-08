package com.nhatnam.server.tools.vmb.repository;

import com.nhatnam.server.tools.vmb.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /**
     * Đếm số vé đang gắn 1 công ty — dùng khi xóa công ty để chặn.
     *
     * ── Lưu ý (G1) ────────────────────────────────────────
     * G1 chưa thêm cột company_id vào Ticket (sẽ thêm ở G2). Trước G2,
     * method này LUÔN trả 0 — chấp nhận vì query lấy 0 rows là hợp lệ.
     * Sau G2 thêm cột, method tự hoạt động nhờ Spring Data derived query.
     */
    long countByCompanyId(Long companyId);
}
