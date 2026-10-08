package com.nhatnam.server.tools.vmb.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Migration idempotent chạy 1 lần lúc khởi động. Mỗi bước độc lập —
 * bước nào lỗi log ERROR đầy đủ, các bước còn lại vẫn chạy.
 *
 * ── 2026-09-19 bổ sung ──────────────────────────────────────
 *   5) Chuyển vmb_ticket.service_fee → 1 dòng vmb_ticket_fee với feeType
 *      suy ra từ Booking.kind (EXCHANGE=CHANGE_TICKET, REFUND=REFUND_TICKET,
 *      còn lại=SPECIAL_ASSISTANCE làm placeholder — user có thể sửa sau).
 *   6) Chuyển vmb_invoice.ticket_id single → bảng join vmb_invoice_ticket.
 *   7) Drop cột vmb_ticket.service_fee (sau khi migrate xong).
 *   8) Drop cột vmb_invoice.ticket_id (sau khi migrate xong).
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class VmbBackfillRunner implements ApplicationRunner, Ordered {

    private final JdbcTemplate jdbc;

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[VMB][Backfill] ▶ Bắt đầu migration");
        step("Nới NULL cho vmb_ticket_file.ticket_id", this::relaxTicketFileTicketIdColumn);
        step("Backfill vmb_ticket_file.booking_id",   this::backfillTicketFileBooking);
        step("Backfill Passenger → Document",         this::backfillPassengerDocuments);
        step("Drop cột cũ ở vmb_passenger",           this::dropLegacyPassengerColumns);

        // ── 2026-09-19 refactor ──────────────────────────────
        step("Migrate service_fee → vmb_ticket_fee",  this::migrateServiceFeeToTicketFees);
        step("Migrate vmb_invoice.ticket_id → join",  this::migrateInvoiceTicketToJoinTable);
        step("Drop vmb_ticket.service_fee",           this::dropServiceFeeColumn);
        step("Drop vmb_invoice.ticket_id",            this::dropInvoiceTicketIdColumn);
        log.info("[VMB][Backfill] ◀ Kết thúc migration");
    }

    private void step(String name, Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.error("[VMB][Backfill] ✗ '{}' THẤT BẠI: {}", name, e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 1) Nới NULL cho vmb_ticket_file.ticket_id
    // ══════════════════════════════════════════════════════════════

    private void relaxTicketFileTicketIdColumn() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT COLUMN_TYPE, IS_NULLABLE
            FROM   information_schema.COLUMNS
            WHERE  TABLE_SCHEMA = DATABASE()
              AND  TABLE_NAME   = 'vmb_ticket_file'
              AND  COLUMN_NAME  = 'ticket_id'
        """);
        if (rows.isEmpty()) {
            log.info("[VMB][Backfill]   • vmb_ticket_file.ticket_id chưa tồn tại — bỏ qua");
            return;
        }
        String columnType = String.valueOf(rows.get(0).get("COLUMN_TYPE"));
        String isNullable = String.valueOf(rows.get(0).get("IS_NULLABLE"));
        if ("YES".equalsIgnoreCase(isNullable)) {
            log.info("[VMB][Backfill]   ✓ vmb_ticket_file.ticket_id ĐÃ NULLABLE ({}), skip", columnType);
            return;
        }
        String sql = "ALTER TABLE vmb_ticket_file MODIFY COLUMN ticket_id " + columnType + " NULL";
        log.info("[VMB][Backfill]   → Chạy: {}", sql);
        jdbc.execute(sql);
        log.info("[VMB][Backfill]   ✓ ĐÃ NULLABLE vmb_ticket_file.ticket_id ({})", columnType);
    }

    // ══════════════════════════════════════════════════════════════
    // 2) Backfill booking_id cho những dòng TicketFile cũ
    // ══════════════════════════════════════════════════════════════

    private void backfillTicketFileBooking() {
        Integer colCount = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM   information_schema.COLUMNS
            WHERE  TABLE_SCHEMA = DATABASE()
              AND  TABLE_NAME   = 'vmb_ticket_file'
              AND  COLUMN_NAME  = 'booking_id'
        """, Integer.class);
        if (colCount == null || colCount == 0) {
            log.info("[VMB][Backfill]   • vmb_ticket_file.booking_id chưa tồn tại — bỏ qua");
            return;
        }
        int updated = jdbc.update("""
            UPDATE vmb_ticket_file f
            JOIN   vmb_ticket t ON t.id = f.ticket_id
            SET    f.booking_id = t.booking_id
            WHERE  f.booking_id IS NULL AND f.ticket_id IS NOT NULL
        """);
        if (updated > 0) log.info("[VMB][Backfill]   ✓ Backfill booking_id: {} dòng", updated);
        else             log.info("[VMB][Backfill]   • Không có dòng nào cần backfill booking_id");
    }

    // ══════════════════════════════════════════════════════════════
    // 3) Passenger cũ → PassengerDocument
    // ══════════════════════════════════════════════════════════════

    private void backfillPassengerDocuments() {
        Integer legacyCols = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM   information_schema.COLUMNS
            WHERE  TABLE_SCHEMA = DATABASE()
              AND  TABLE_NAME   = 'vmb_passenger'
              AND  COLUMN_NAME IN ('cccd_no','passport_no')
        """, Integer.class);
        if (legacyCols == null || legacyCols == 0) {
            log.info("[VMB][Backfill]   • Cột cũ ở vmb_passenger đã bị drop — bỏ qua");
            return;
        }
        List<Map<String, Object>> legacyRows = jdbc.queryForList("""
            SELECT id, cccd_no, passport_no, expiry_date,
                   cccd_file, cccd_original, passport_file, passport_original
            FROM   vmb_passenger
            WHERE  (cccd_no IS NOT NULL AND cccd_no <> '')
                OR (passport_no IS NOT NULL AND passport_no <> '')
                OR (cccd_file IS NOT NULL AND cccd_file <> '')
                OR (passport_file IS NOT NULL AND passport_file <> '')
        """);
        if (legacyRows.isEmpty()) {
            log.info("[VMB][Backfill]   • Không có passenger cũ cần migrate document");
            return;
        }
        long now = System.currentTimeMillis();
        int createdCccd = 0, createdPassport = 0;
        for (Map<String, Object> row : legacyRows) {
            Long paxId       = ((Number) row.get("id")).longValue();
            String cccdNo    = str(row.get("cccd_no"));
            String ppNo      = str(row.get("passport_no"));
            String expiry    = str(row.get("expiry_date"));
            String cccdFile  = str(row.get("cccd_file"));
            String cccdOrig  = str(row.get("cccd_original"));
            String ppFile    = str(row.get("passport_file"));
            String ppOrig    = str(row.get("passport_original"));
            if (notBlank(cccdNo) || notBlank(cccdFile)) {
                if (!documentExists(paxId, "CCCD")) {
                    insertDoc(paxId, "CCCD", cccdNo, null, null, expiry, cccdFile, cccdOrig, now);
                    createdCccd++;
                }
            }
            if (notBlank(ppNo) || notBlank(ppFile)) {
                if (!documentExists(paxId, "PASSPORT")) {
                    insertDoc(paxId, "PASSPORT", ppNo, null, null, expiry, ppFile, ppOrig, now);
                    createdPassport++;
                }
            }
        }
        if (createdCccd + createdPassport > 0) {
            log.info("[VMB][Backfill]   ✓ Passenger→Document: +{} CCCD, +{} PASSPORT (từ {} passenger cũ)",
                    createdCccd, createdPassport, legacyRows.size());
        } else {
            log.info("[VMB][Backfill]   • Tất cả document đã được migrate trước đó");
        }
    }

    private boolean documentExists(Long paxId, String type) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vmb_passenger_document WHERE passenger_id = ? AND type = ?",
                Integer.class, paxId, type);
        return cnt != null && cnt > 0;
    }

    private void insertDoc(Long paxId, String type,
                           String docNumber, String nationality,
                           String issueDate, String expiryDate,
                           String storedFile, String originalFile,
                           long now) {
        jdbc.update("""
            INSERT INTO vmb_passenger_document
                (passenger_id, type, doc_number, nationality, issue_date, expiry_date,
                 stored_file, original_file, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, paxId, type, docNumber, nationality, issueDate, expiryDate,
                storedFile, originalFile, now, now);
    }

    // ══════════════════════════════════════════════════════════════
    // 4) Drop cột cũ khỏi vmb_passenger
    // ══════════════════════════════════════════════════════════════

    private void dropLegacyPassengerColumns() {
        String[] cols = {
                "cccd_no", "passport_no", "expiry_date",
                "cccd_file", "cccd_original",
                "passport_file", "passport_original"
        };
        int dropped = 0;
        for (String col : cols) {
            try {
                Integer exists = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM   information_schema.COLUMNS
                    WHERE  TABLE_SCHEMA = DATABASE()
                      AND  TABLE_NAME   = 'vmb_passenger'
                      AND  COLUMN_NAME  = ?
                """, Integer.class, col);
                if (exists != null && exists > 0) {
                    jdbc.execute("ALTER TABLE vmb_passenger DROP COLUMN " + col);
                    dropped++;
                }
            } catch (Exception e) {
                log.warn("[VMB][Backfill]     ! DROP COLUMN {} lỗi: {}", col, e.getMessage());
            }
        }
        if (dropped > 0) log.info("[VMB][Backfill]   ✓ Drop {} cột cũ khỏi vmb_passenger", dropped);
        else             log.info("[VMB][Backfill]   • Cột cũ đã drop hết từ trước");
    }

    // ══════════════════════════════════════════════════════════════
    // 5) Migrate vmb_ticket.service_fee → vmb_ticket_fee
    // ══════════════════════════════════════════════════════════════
    //
    // Chỉ chạy nếu cột service_fee còn tồn tại. Với mỗi ticket có service_fee
    // không rỗng và không "0", tạo 1 dòng TicketFee với:
    //   - feeType suy ra từ Booking.kind:
    //       EXCHANGE → CHANGE_TICKET
    //       REFUND   → REFUND_TICKET
    //       khác     → SPECIAL_ASSISTANCE (placeholder; user sửa sau)
    //   - amount = service_fee cũ
    //   - note   = "Migrated từ service_fee cũ"
    //   - orderIdx = 0
    //
    // Idempotent: chỉ tạo khi ticket CHƯA có TicketFee nào (kiểm để không chạy 2 lần).

    private void migrateServiceFeeToTicketFees() {
        Integer colExists = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='vmb_ticket' AND COLUMN_NAME='service_fee'
        """, Integer.class);
        if (colExists == null || colExists == 0) {
            log.info("[VMB][Backfill]   • vmb_ticket.service_fee đã bị drop — bỏ qua migrate");
            return;
        }

        Integer feeTableExists = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.TABLES
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='vmb_ticket_fee'
        """, Integer.class);
        if (feeTableExists == null || feeTableExists == 0) {
            log.info("[VMB][Backfill]   • vmb_ticket_fee chưa tồn tại (Hibernate sẽ tạo) — chạy lại lần sau");
            return;
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT t.id AS ticket_id, t.service_fee, b.kind
            FROM   vmb_ticket t
            JOIN   vmb_booking b ON b.id = t.booking_id
            WHERE  t.service_fee IS NOT NULL
              AND  t.service_fee <> ''
              AND  t.service_fee <> '0'
              AND  NOT EXISTS (SELECT 1 FROM vmb_ticket_fee f WHERE f.ticket_id = t.id)
        """);
        if (rows.isEmpty()) {
            log.info("[VMB][Backfill]   • Không có service_fee cần migrate (hoặc đã migrate xong)");
            return;
        }

        long now = System.currentTimeMillis();
        int inserted = 0;
        for (Map<String, Object> row : rows) {
            Long tid  = ((Number) row.get("ticket_id")).longValue();
            String sf = str(row.get("service_fee"));
            String kind = str(row.get("kind"));
            String feeType = switch (kind == null ? "" : kind) {
                case "EXCHANGE" -> "CHANGE_TICKET";
                case "REFUND"   -> "REFUND_TICKET";
                default         -> "SPECIAL_ASSISTANCE";
            };
            jdbc.update("""
                INSERT INTO vmb_ticket_fee (ticket_id, fee_type, amount, note, order_idx, created_at)
                VALUES (?, ?, ?, ?, 0, ?)
            """, tid, feeType, sf, "Migrated từ service_fee cũ", now);
            inserted++;
        }
        log.info("[VMB][Backfill]   ✓ Migrate service_fee → vmb_ticket_fee: {} dòng", inserted);
    }

    // ══════════════════════════════════════════════════════════════
    // 6) Migrate vmb_invoice.ticket_id → vmb_invoice_ticket
    // ══════════════════════════════════════════════════════════════

    private void migrateInvoiceTicketToJoinTable() {
        Integer colExists = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='vmb_invoice' AND COLUMN_NAME='ticket_id'
        """, Integer.class);
        if (colExists == null || colExists == 0) {
            log.info("[VMB][Backfill]   • vmb_invoice.ticket_id đã bị drop — bỏ qua migrate");
            return;
        }
        Integer joinExists = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.TABLES
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='vmb_invoice_ticket'
        """, Integer.class);
        if (joinExists == null || joinExists == 0) {
            log.info("[VMB][Backfill]   • vmb_invoice_ticket chưa tồn tại (Hibernate sẽ tạo) — chạy lại lần sau");
            return;
        }

        // Chỉ insert dòng chưa có (idempotent). ticket_id NULL trong cột cũ có
        // nghĩa "chung cả booking" → KHÔNG insert gì (set rỗng).
        int inserted = jdbc.update("""
            INSERT IGNORE INTO vmb_invoice_ticket (invoice_id, ticket_id)
            SELECT i.id, i.ticket_id
            FROM   vmb_invoice i
            WHERE  i.ticket_id IS NOT NULL
        """);
        if (inserted > 0) log.info("[VMB][Backfill]   ✓ Migrate invoice→ticket join: {} dòng", inserted);
        else              log.info("[VMB][Backfill]   • Không có invoice.ticket_id cần migrate");
    }

    // ══════════════════════════════════════════════════════════════
    // 7 & 8) Drop cột cũ (chỉ khi migrate đã xong)
    // ══════════════════════════════════════════════════════════════

    private void dropServiceFeeColumn() {
        // Chỉ drop khi bảng vmb_ticket_fee tồn tại (nghĩa là migrate đã có chỗ chạy)
        Integer feeTable = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.TABLES
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='vmb_ticket_fee'
        """, Integer.class);
        if (feeTable == null || feeTable == 0) {
            log.info("[VMB][Backfill]   • vmb_ticket_fee chưa tồn tại — chưa drop service_fee");
            return;
        }
        Integer colExists = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='vmb_ticket' AND COLUMN_NAME='service_fee'
        """, Integer.class);
        if (colExists == null || colExists == 0) {
            log.info("[VMB][Backfill]   • vmb_ticket.service_fee đã bị drop");
            return;
        }
        try {
            jdbc.execute("ALTER TABLE vmb_ticket DROP COLUMN service_fee");
            log.info("[VMB][Backfill]   ✓ Đã drop vmb_ticket.service_fee");
        } catch (Exception e) {
            log.warn("[VMB][Backfill]     ! DROP service_fee lỗi: {}", e.getMessage());
        }
    }

    private void dropInvoiceTicketIdColumn() {
        Integer joinTable = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.TABLES
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='vmb_invoice_ticket'
        """, Integer.class);
        if (joinTable == null || joinTable == 0) {
            log.info("[VMB][Backfill]   • vmb_invoice_ticket chưa tồn tại — chưa drop invoice.ticket_id");
            return;
        }
        Integer colExists = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='vmb_invoice' AND COLUMN_NAME='ticket_id'
        """, Integer.class);
        if (colExists == null || colExists == 0) {
            log.info("[VMB][Backfill]   • vmb_invoice.ticket_id đã bị drop");
            return;
        }
        try {
            // Drop FK trước nếu có (MySQL: tra tên constraint từ information_schema.KEY_COLUMN_USAGE)
            List<Map<String, Object>> fks = jdbc.queryForList("""
                SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='vmb_invoice'
                  AND COLUMN_NAME='ticket_id' AND REFERENCED_TABLE_NAME IS NOT NULL
            """);
            for (Map<String, Object> fk : fks) {
                String name = String.valueOf(fk.get("CONSTRAINT_NAME"));
                try {
                    jdbc.execute("ALTER TABLE vmb_invoice DROP FOREIGN KEY " + name);
                } catch (Exception ex) {
                    log.warn("[VMB][Backfill]     ! DROP FK {} lỗi (bỏ qua): {}", name, ex.getMessage());
                }
            }
            jdbc.execute("ALTER TABLE vmb_invoice DROP COLUMN ticket_id");
            log.info("[VMB][Backfill]   ✓ Đã drop vmb_invoice.ticket_id");
        } catch (Exception e) {
            log.warn("[VMB][Backfill]     ! DROP invoice.ticket_id lỗi: {}", e.getMessage());
        }
    }

    // ── Helpers ──
    private static String str(Object o)      { return o == null ? null : o.toString(); }
    private static boolean notBlank(String s){ return s != null && !s.isBlank(); }
}