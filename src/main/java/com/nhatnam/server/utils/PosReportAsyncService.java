package com.nhatnam.server.utils;

import com.nhatnam.server.service.PosExcelReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Log4j2
public class PosReportAsyncService {

    private final PosExcelReportService reportService;
    private final TelegramService telegramService;

    /**
     * Generate and send range report asynchronously.
     * @Transactional keeps Hibernate session open during async execution
     * to avoid LazyInitializationException when accessing lazy-loaded entities.
     */
    @Transactional(readOnly = true)
    public void generateAndSendRangeReport(String from, String to, Long userId, String storeName) {
        try {
            byte[] data = reportService.generateRangeReport(from, to, userId);

            telegramService.sendDocumentByGroupName("pos", data,
                    "shift-report-" + from + "_" + to + ".xlsx",
                    "📊 Báo cáo " + storeName + " các ca từ " + from + " đến " + to,
                    null);
        } catch (Exception e) {
            log.error("[POS-ASYNC] Failed to generate range report for userId={}", userId, e);
        }
    }
}