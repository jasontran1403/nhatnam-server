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
import java.util.List;

@Service
@RequiredArgsConstructor
@Log4j2
public class PosDiscountService {

    private final PosDiscountProgramRepository  programRepo;
    private final PosCustomerDiscountRepository customerDiscountRepo;
    private final PosCustomerRepository         customerRepo;
    private final PosOrderRepository            orderRepo;

    // ── DTO: kết quả tính discount ───────────────────────────────
    public record DiscountResult(
            BigDecimal discountAmount,   // tiền giảm thực tế
            BigDecimal finalAmount,      // subtotal - discountAmount
            boolean    isCapped,         // true nếu bị giới hạn bởi budget/maxPerUse
            String     note,             // VD: "Chỉ còn 5,000đ hạn mức"
            boolean    isExhausted       // true nếu đã hết hạn mức
    ) {}

    // ── Tính discount cho 1 đơn ──────────────────────────────────
    /**
     * @param customerDiscount  bản ghi discount của khách
     * @param subtotal          tổng bill chưa giảm
     * @param selectedItemPrice giá của món được chọn (chỉ dùng cho ITEM type, null nếu BILL type)
     */
    public DiscountResult calculate(
            PosCustomerDiscount customerDiscount,
            BigDecimal subtotal,
            BigDecimal selectedItemPrice
    ) {
        PosDiscountOption option = customerDiscount.getSelectedOption();
        if (option == null) {
            return new DiscountResult(BigDecimal.ZERO, subtotal, false,
                    "Chưa chọn loại giảm giá", false);
        }

        PosDiscountProgram program = customerDiscount.getProgram();
        BigDecimal remaining = program.getMaxDiscountPerCustomer()
                .subtract(customerDiscount.getBudgetUsed());

        // Hết hạn mức
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return new DiscountResult(BigDecimal.ZERO, subtotal, false,
                    "Hết hạn mức giảm giá", true);
        }

        // Tính raw discount theo type
        BigDecimal raw;
        BigDecimal base = option.isItemType()
                ? (selectedItemPrice != null ? selectedItemPrice : BigDecimal.ZERO)
                : subtotal;

        raw = switch (option.getDiscountType()) {
            case PERCENT_BILL, PERCENT_ITEM ->
                    base.multiply(option.getDiscountValue())
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            case FIXED_BILL, FIXED_ITEM ->
                    option.getDiscountValue().min(base);  // không giảm quá giá món/bill
        };

        // Cap theo maxPerUse
        if (option.getMaxPerUse() != null) {
            raw = raw.min(option.getMaxPerUse());
        }

        // Cap theo remaining budget
        boolean isCapped = raw.compareTo(remaining) > 0;
        BigDecimal effective = raw.min(remaining);

        String note = null;
        if (isCapped) {
            note = String.format("Hạn mức còn %,.0fđ, chỉ giảm được %,.0fđ",
                    remaining, effective);
        }

        BigDecimal finalAmt = subtotal.subtract(effective).max(BigDecimal.ZERO);
        return new DiscountResult(effective, finalAmt, isCapped, note, false);
    }

    // ── Commit sau khi tạo order thành công ──────────────────────
    @Transactional
    public void commitDiscount(PosCustomerDiscount cd, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return;
        cd.setBudgetUsed(cd.getBudgetUsed().add(amount));
        customerDiscountRepo.save(cd);
        log.info("[Discount] customer={} budgetUsed={}/{}",
                cd.getCustomer().getPhone(), cd.getBudgetUsed(),
                cd.getProgram().getMaxDiscountPerCustomer());
    }

    // ── Customer chọn option ──────────────────────────────────────
    @Transactional
    public PosCustomerDiscount chooseOption(Long customerDiscountId, Long optionId) {
        PosCustomerDiscount cd = customerDiscountRepo.findById(customerDiscountId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy discount record"));

        PosDiscountOption option = cd.getProgram().getOptions().stream()
                .filter(o -> o.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Option không thuộc chương trình này"));

        cd.setSelectedOption(option);
        cd.setOptionChosenAt(System.currentTimeMillis());
        return customerDiscountRepo.save(cd);
    }

    // ── Lấy discount đang active của customer ─────────────────────
    public List<PosCustomerDiscount> getActiveDiscounts(Long customerId) {
        return customerDiscountRepo.findActiveByCustomerId(
                customerId, System.currentTimeMillis());
    }

    // ── SCHEDULED: Check qualify mỗi giờ ─────────────────────────
    // Tìm customers đủ điều kiện và tạo PosCustomerDiscount record
    @Scheduled(cron = "0 0 * * * *")   // mỗi đầu giờ
    @Transactional
    public void checkQualifyScheduled() {
        long now = System.currentTimeMillis();
        List<PosDiscountProgram> programs = programRepo.findActiveApplyingPrograms(now);

        for (PosDiscountProgram program : programs) {
            qualifyCustomersForProgram(program, now);
        }
    }

    // ── Manual trigger (khi admin bật program) ────────────────────
    @Transactional
    public int qualifyCustomersForProgram(PosDiscountProgram program, long now) {
        int count = 0;
        // Lấy tất cả customers có chi tiêu đủ trong kỳ qualify
        List<Object[]> rows = orderRepo.sumSpendByCustomerInRange(
                program.getQualifyFrom(), program.getQualifyTo());

        for (Object[] row : rows) {
            String phone     = (String) row[0];
            BigDecimal spend = (BigDecimal) row[1];

            if (spend.compareTo(program.getMinSpend()) < 0) continue;

            customerRepo.findByPhone(phone).ifPresent(customer -> {
                // Tạo record nếu chưa có
                customerDiscountRepo.findByCustomerIdAndProgramId(
                                customer.getId(), program.getId())
                        .orElseGet(() -> {
                            PosCustomerDiscount cd = PosCustomerDiscount.builder()
                                    .customer(customer)
                                    .program(program)
                                    .qualifiedAt(now)
                                    .build();
                            customerDiscountRepo.save(cd);
                            log.info("[Discount] Qualified: phone={} program={}",
                                    phone, program.getName());
                            return cd;
                        });
            });
            count++;
        }
        return count;
    }

    // ── Admin: kết thúc program sớm ───────────────────────────────
    @Transactional
    public PosDiscountProgram endProgram(Long programId, String reason) {
        PosDiscountProgram p = programRepo.findById(programId)
                .orElseThrow(() -> new RuntimeException("Program not found"));
        p.setStatus(PosDiscountProgram.ProgramStatus.CANCELLED);
        p.setEndedAt(System.currentTimeMillis());
        p.setEndedReason(reason);
        return programRepo.save(p);
    }
}