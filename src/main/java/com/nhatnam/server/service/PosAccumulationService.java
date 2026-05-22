package com.nhatnam.server.service;

import com.nhatnam.server.entity.pos.*;
import com.nhatnam.server.repository.pos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Log4j2
public class PosAccumulationService {

    private static final ZoneId VN  = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final BigDecimal ACCUMULATE_RATE    = new BigDecimal("0.05"); // 5%
    private static final BigDecimal REFERRAL_RATE      = new BigDecimal("0.05"); // 5%
    private static final long       CREDIT_EXPIRE_MONTHS  = 6;
    private static final long       VOUCHER_EXPIRE_MONTHS = 1;
    private static final DateTimeFormatter MONTH_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM");
    private final PosCustomerTypeRateRepository customerTypeRateRepo;


    private final PosAccumulationRecordRepository accumulationRepo;
    private final PosAccumulationLogRepository    accLogRepo;
    private final PosCreditNoteRepository         creditNoteRepo;
    private final PosEVoucherRepository           eVoucherRepo;
    private final PosVoucherTemplateRepository    templateRepo;
    private final PosCustomerRepository           customerRepo;

    private static final BigDecimal DEFAULT_ACCUM_RATE    = new BigDecimal("0.05");
    private static final BigDecimal DEFAULT_REFERRAL_RATE = new BigDecimal("0.05");

    // ── 1. Ghi nhận tích lũy khi tạo đơn ────────────────────────

    @Transactional
    public void recordSpend(Long customerId, Long storeId,
                            Long orderId, BigDecimal spendNet) {
        if (spendNet == null || spendNet.compareTo(BigDecimal.ZERO) <= 0) return;

        PosCustomer customer = customerRepo.findById(customerId).orElse(null);
        if (customer == null) return;

        String month = YearMonth.now(VN).format(MONTH_FMT);
        long   now   = System.currentTimeMillis();

        // Lấy rate theo customerType của người mua
        String typeCode = customer.getCustomerType() != null
                ? customer.getCustomerType().name() : "KLE";
        PosCustomerTypeRate typeRate = getTypeRate(storeId, typeCode);

        BigDecimal accumRate = typeRate != null
                ? typeRate.getAccumRate() : DEFAULT_ACCUM_RATE;

        BigDecimal selfCredit = spendNet
                .multiply(accumRate)
                .setScale(0, RoundingMode.FLOOR);

        // Ghi log bản thân
        accLogRepo.save(PosAccumulationLog.builder()
                .customer(customer)
                .storeId(storeId)
                .orderId(orderId)
                .spendNet(spendNet)
                .month(month)
                .logType(PosAccumulationLog.LogType.SELF)
                .createdAt(now)
                .build());

        _upsertRecord(customer, storeId, month, spendNet, selfCredit, now);

        // Referral bonus
        if (customer.getReferredByCustomerId() != null) {
            PosCustomer referrer = customerRepo
                    .findById(customer.getReferredByCustomerId().longValue())
                    .orElse(null);
            if (referrer != null) {
                BigDecimal referralRate = typeRate != null
                        ? typeRate.getReferralRate() : DEFAULT_REFERRAL_RATE;

                BigDecimal referralBonus = spendNet
                        .multiply(referralRate)
                        .setScale(0, RoundingMode.FLOOR);

                accLogRepo.save(PosAccumulationLog.builder()
                        .customer(referrer)
                        .storeId(storeId)
                        .orderId(orderId)
                        .spendNet(referralBonus)
                        .month(month)
                        .logType(PosAccumulationLog.LogType.REFERRAL_BONUS)
                        .referredCustomerId(customerId)
                        .createdAt(now)
                        .build());

                _upsertRecord(referrer, storeId, month, referralBonus, referralBonus, now);
            }
        }
    }

    private void _upsertRecord(PosCustomer customer, Long storeId,
                               String month,
                               BigDecimal spendToAdd,
                               BigDecimal creditToAdd,
                               long now) {
        PosAccumulationRecord rec = accumulationRepo
                .findByCustomerIdAndStoreIdAndMonth(
                        customer.getId(), storeId, month)
                .orElseGet(() -> PosAccumulationRecord.builder()
                        .customer(customer)
                        .storeId(storeId)
                        .month(month)
                        .totalSpendNet(BigDecimal.ZERO)
                        .creditAmount(BigDecimal.ZERO)
                        .settled(false)
                        .createdAt(now)
                        .build());

        rec.setTotalSpendNet(rec.getTotalSpendNet().add(spendToAdd));
        rec.setCreditAmount(rec.getCreditAmount().add(creditToAdd)); // ← cộng dồn
        rec.setUpdatedAt(now);
        accumulationRepo.save(rec);
    }

    // ── 2. Chốt tháng — sinh credit note ─────────────────────────
    // Chạy lúc 23:59 ngày cuối tháng

    @Scheduled(cron = "0 59 23 L * ?", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void settleMonth() {
        String month = YearMonth.now(VN).format(MONTH_FMT);
        log.info("[Accumulation] Settling month: {}", month);

        List<PosAccumulationRecord> records =
                accumulationRepo.findAllByMonthAndSettledFalse(month);

        long now = System.currentTimeMillis();
        long expiredAt = LocalDate.now(VN)
                .plusMonths(CREDIT_EXPIRE_MONTHS + 1)
                .withDayOfMonth(1)
                .atStartOfDay(VN).toInstant().toEpochMilli();

        for (PosAccumulationRecord rec : records) {
            if (rec.getCreditAmount().compareTo(BigDecimal.ZERO) <= 0) continue;

            creditNoteRepo.save(PosCreditNote.builder()
                    .customer(rec.getCustomer())
                    .storeId(rec.getStoreId())
                    .sourceMonth(month)
                    .type(PosCreditNote.CreditNoteType.SPEND)
                    .amount(rec.getCreditAmount())
                    .remainingAmount(rec.getCreditAmount())
                    .status(PosCreditNote.CreditNoteStatus.ACTIVE)
                    .expiredAt(expiredAt)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());

            rec.setSettled(true);
            rec.setSettledAt(now);
            rec.setUpdatedAt(now);
            accumulationRepo.save(rec);

            log.info("[Accumulation] Credit note created: customerId={} amount={}",
                    rec.getCustomer().getId(), rec.getCreditAmount());
        }
    }

    // ── 3. Expire credit notes & vouchers ────────────────────────
    // Chạy lúc 1:00 sáng mỗi ngày

    @Scheduled(cron = "0 0 2 * * ?", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void expireStale() {
        long now = System.currentTimeMillis();

        // Expire credit notes
        List<PosCreditNote> expiredNotes = creditNoteRepo
                .findByExpiredAtBeforeAndStatusIn(now,
                        List.of(PosCreditNote.CreditNoteStatus.ACTIVE,
                                PosCreditNote.CreditNoteStatus.PARTIALLY_USED));
        for (PosCreditNote n : expiredNotes) {
            n.setStatus(PosCreditNote.CreditNoteStatus.EXPIRED);
            n.setUpdatedAt(now);
        }
        creditNoteRepo.saveAll(expiredNotes);

        // Expire vouchers
        List<PosEVoucher> expiredVouchers = eVoucherRepo
                .findByExpiredAtBeforeAndStatus(now, PosEVoucher.EVoucherStatus.ACTIVE);
        for (PosEVoucher v : expiredVouchers) {
            v.setStatus(PosEVoucher.EVoucherStatus.EXPIRED);
        }
        eVoucherRepo.saveAll(expiredVouchers);

        log.info("[Accumulation] Expired {} notes, {} vouchers",
                expiredNotes.size(), expiredVouchers.size());
    }

    // ── 4. Redeem credit → EVoucher ───────────────────────────────

    @Transactional
    public PosEVoucher redeemCredit(Long customerId, Long storeId,
                                    Long templateId) {  // ← bỏ creditNoteId
        PosVoucherTemplate template = templateRepo.findById(templateId)
                .orElseThrow(() -> new RuntimeException("Template không tồn tại"));
        if (!template.getStoreId().equals(storeId))
            throw new RuntimeException("Template không thuộc store này");
        if (!template.isActive())
            throw new RuntimeException("Template không còn hoạt động");

        // Lấy tất cả credit note còn dùng được, sắp xếp theo expired_at ASC (cũ nhất trước)
        List<PosCreditNote> notes = creditNoteRepo
                .findByCustomerIdAndStoreIdAndStatusIn(
                        customerId, storeId,
                        List.of(PosCreditNote.CreditNoteStatus.ACTIVE,
                                PosCreditNote.CreditNoteStatus.PARTIALLY_USED))
                .stream()
                .filter(n -> n.getExpiredAt() > System.currentTimeMillis())
                .sorted(Comparator.comparing(PosCreditNote::getExpiredAt))  // cũ nhất trước
                .toList();

        // Tính tổng credit khả dụng
        BigDecimal totalAvailable = notes.stream()
                .map(PosCreditNote::getRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalAvailable.compareTo(template.getCreditCost()) < 0)
            throw new RuntimeException("Không đủ credit. Cần "
                    + template.getCreditCost() + ", còn " + totalAvailable);

        long now = System.currentTimeMillis();

        // Trừ dần từ note cũ nhất
        BigDecimal remaining = template.getCreditCost();
        for (PosCreditNote note : notes) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal deduct = remaining.min(note.getRemainingAmount());
            note.setRemainingAmount(note.getRemainingAmount().subtract(deduct));
            remaining = remaining.subtract(deduct);

            if (note.getRemainingAmount().compareTo(BigDecimal.ZERO) == 0)
                note.setStatus(PosCreditNote.CreditNoteStatus.EXHAUSTED);
            else
                note.setStatus(PosCreditNote.CreditNoteStatus.PARTIALLY_USED);

            note.setUpdatedAt(now);
            creditNoteRepo.save(note);
        }

        // Tạo voucher
        long expiredAt = LocalDate.now(VN)
                .plusMonths(VOUCHER_EXPIRE_MONTHS)
                .atStartOfDay(VN).toInstant().toEpochMilli();

        PosCustomer customer = customerRepo.findById(customerId).orElseThrow();

        PosEVoucher voucher = PosEVoucher.builder()
                .code(generateVoucherCode())
                .customer(customer)
                .storeId(storeId)
                .creditNote(notes.get(0))  // gắn note đầu tiên (cũ nhất) làm reference
                .template(template)
                .creditUsed(template.getCreditCost())
                .voucherValue(template.getDiscountAmount())
                .status(PosEVoucher.EVoucherStatus.ACTIVE)
                .expiredAt(expiredAt)
                .createdAt(now)
                .build();

        return eVoucherRepo.save(voucher);
    }

    // ── 5. Apply voucher vào đơn ──────────────────────────────────

    @Transactional
    public BigDecimal applyVoucher(String voucherCode, Long orderId,
                                   BigDecimal orderTotal,
                                   Long discountItemProductId,
                                   BigDecimal itemSubtotal) {
        PosEVoucher voucher = eVoucherRepo.findByCode(voucherCode)
                .orElseThrow(() -> new RuntimeException("Voucher không tồn tại: " + voucherCode));

        if (voucher.getStatus() != PosEVoucher.EVoucherStatus.ACTIVE)
            throw new RuntimeException("Voucher đã được sử dụng hoặc hết hạn");
        if (voucher.getExpiredAt() < System.currentTimeMillis())
            throw new RuntimeException("Voucher đã hết hạn");

        PosVoucherTemplate tmpl = voucher.getTemplate();
        BigDecimal discount;

        discount = switch (tmpl.getVoucherType()) {
            case FIXED_AMOUNT ->
                    voucher.getVoucherValue().min(orderTotal);
        };

        // Không hoàn tiền — chỉ trừ tối đa bằng orderTotal
        discount = discount.min(orderTotal);

        // Mark used
        voucher.setStatus(PosEVoucher.EVoucherStatus.USED);
        voucher.setUsedOrderId(orderId);
        voucher.setUsedAt(System.currentTimeMillis());
        eVoucherRepo.save(voucher);

        return discount;
    }

    // ── 6. Query helpers ──────────────────────────────────────────

    public List<PosCreditNote> getActiveCreditNotes(Long customerId, Long storeId) {
        return creditNoteRepo.findByCustomerIdAndStoreIdAndStatusIn(
                customerId, storeId,
                List.of(PosCreditNote.CreditNoteStatus.ACTIVE,
                        PosCreditNote.CreditNoteStatus.PARTIALLY_USED));
    }

    public List<PosVoucherTemplate> getTemplates(Long storeId) {
        return templateRepo.findByStoreIdAndActiveTrue(storeId);
    }

    public List<PosEVoucher> getActiveVouchers(Long customerId, Long storeId) {
        return eVoucherRepo.findByCustomerIdAndStoreIdAndStatus(
                customerId, storeId, PosEVoucher.EVoucherStatus.ACTIVE);
    }

    public PosAccumulationRecord getCurrentMonthRecord(
            Long customerId, Long storeId) {
        String month = YearMonth.now(VN).format(MONTH_FMT);
        return accumulationRepo.findByCustomerIdAndStoreIdAndMonth(
                customerId, storeId, month).orElse(null);
    }

    // ── Helpers ───────────────────────────────────────────────────

    private String generateVoucherCode() {
        return "EV-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 10).toUpperCase();
    }

    private PosCustomerTypeRate getTypeRate(Long storeId, String typeCode) {
        return customerTypeRateRepo
                .findByStoreIdAndTypeCode(storeId, typeCode)
                .orElse(null);
    }
}