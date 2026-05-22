package com.nhatnam.server.utils;

import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.IngredientRepository;
import com.nhatnam.server.repository.InventoryBatchRepository;
import com.nhatnam.server.repository.OrderItemIngredientRepository;
import com.nhatnam.server.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
@RequiredArgsConstructor
@Log4j2
public class IngredientReportExport {

    private final InventoryBatchRepository      batchRepository;
    private final IngredientRepository          ingredientRepository;
    private final OrderRepository               orderRepository;
    private final OrderItemIngredientRepository orderItemIngredientRepository;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter D_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // ── Palette ──────────────────────────────────────────────────
    private static final String HEADER_BG   = "1E3A5F";
    private static final String HEADER_FG   = "FFFFFF";
    private static final String SUB_BG      = "2E6DA4";
    private static final String COL_HEAD    = "D6E4F0";
    private static final String COL_HEAD_FG = "1E3A5F";
    private static final String ROW_ODD     = "F0F7FF";
    private static final String ROW_EVEN    = "FFFFFF";
    private static final String STOCK_HEAD  = "E65100";
    private static final String STOCK_BG    = "FFF8E1";

    private static final Map<String, String> ACTION_BG = Map.of(
            "IMPORT", "E8F5E9",
            "EXPORT", "FFF3E0",
            "ADJUST", "F3E5F5",
            "SALE",   "FCE4EC"
    );
    private static final Map<String, String> ACTION_QTY_FG = Map.of(
            "IMPORT", "1B5E20",
            "EXPORT", "BF360C",
            "ADJUST", "4A148C",
            "SALE",   "880E4F"
    );
    private static final Map<String, String> ACTION_LABEL_FG = Map.of(
            "NHẬP",       "2E7D32",
            "XUẤT",       "E65100",
            "ĐIỀU CHỈNH", "6A1B9A",
            "XUẤT BÁN",   "880E4F"
    );

    // ── Column indices ────────────────────────────────────────────
    // Col 0: STT
    // Col 1: Nhà cung cấp
    // Col 2: Mã phiếu
    // Col 3: Thời gian
    // Col 4: Note  ← MỚI
    // Col 5: Tên nguyên liệu
    // Col 6: ĐVT
    // Col 7: Số lượng
    // Col 8: gap
    // Col 9: Tên NL (tồn kho)
    // Col 10: ĐVT
    // Col 11: Tồn

    // ── Internal DTOs ─────────────────────────────────────────────
    private record ReportRow(
            String     actionKey,
            String     actionLabel,
            String     batchCode,
            String     supplier,    // tên NCC snapshot, "" nếu SALE/ADJUST
            String     timeStr,
            long       epochMs,
            String     note,        // reason stripped, "" nếu SALE
            String     ingredientName,
            String     unit,
            BigDecimal qty
    ) {}

    private record AggIngredient(String name, String unit, BigDecimal qty) {}

    // ════════════════════════════════════════════════════════════
    // PUBLIC ENTRY
    // ════════════════════════════════════════════════════════════
    public byte[] export(long fromTs, long toTs) throws Exception {

        List<ReportRow>  rows           = buildRows(fromTs, toTs);
        List<Ingredient> allIngredients =
                ingredientRepository.findByIsActiveTrueOrderByNameAsc();

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            XSSFSheet ws = wb.createSheet("Báo cáo kho");
            ws.createFreezePane(0, 4);

            // Cols 0–7: left table | Col 8: gap | Cols 9–11: right table
            int[] widths = {5, 26, 30, 18, 28, 26, 10, 12, 3, 26, 10, 12};
            for (int i = 0; i < widths.length; i++)
                ws.setColumnWidth(i, widths[i] * 256);

            StyleCache sc = new StyleCache(wb);

            String fromStr = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(fromTs), VN).format(D_FMT);
            String toStr   = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(toTs), VN).format(D_FMT);

            buildTitleRow(ws, sc, 0,
                    "BÁO CÁO XUẤT / NHẬP KHO", "TỒN KHO HIỆN TẠI");
            buildSubtitleRow(ws, sc, 1,
                    "Từ ngày: " + fromStr + "   →   Đến ngày: " + toStr);
            buildSpacerRow(ws, sc, 2);
            buildColumnHeaderRow(ws, sc, 3);

            writeDataRows(ws, sc, rows, 4);
            buildStockTable(ws, sc, allIngredients);

            PrintSetup ps = ws.getPrintSetup();
            ps.setLandscape(true);
            ps.setFitWidth((short) 1);
            ps.setFitHeight((short) 0);
            ws.setFitToPage(true);
            ws.setRepeatingRows(new CellRangeAddress(0, 3, 0, 0));

            wb.write(out);
            return out.toByteArray();
        }
    }

    // ════════════════════════════════════════════════════════════
    // BUILD ROWS
    // ════════════════════════════════════════════════════════════
    private List<ReportRow> buildRows(long fromTs, long toTs) {
        List<ReportRow> result = new ArrayList<>();

        // ── 1. Batch (IMPORT / EXPORT / ADJUST) ─────────────────
        List<InventoryBatch> batches =
                batchRepository.findByCreatedAtBetweenWithLogs(fromTs, toTs);

        for (InventoryBatch batch : batches) {
            String actionKey   = batch.getAction().name();
            String actionLabel = switch (actionKey) {
                case "IMPORT" -> "NHẬP";
                case "EXPORT" -> "XUẤT";
                default        -> "ĐIỀU CHỈNH";
            };
            long   epoch       = batch.getCreatedAt() != null ? batch.getCreatedAt() : 0L;
            String timeStr     = toTimeStr(epoch);
            String code        = "[" + actionLabel + "]  " + batch.getBatchCode();

            // Tên NCC: ưu tiên supplierName snapshot trong batch
            String supplierName = firstNonBlank(batch.getSupplierName(),
                    batch.getSupplierRef(), "");

            // Note của batch (lý do nhập/xuất)
            String batchNote = firstNonBlank(batch.getNote(), "");

            batch.getLogs().stream()
                    .filter(log -> log.getQuantity().compareTo(BigDecimal.ZERO) != 0)
                    .sorted(Comparator.comparing(l -> l.getIngredient().getName()))
                    .forEach(log -> result.add(new ReportRow(
                            actionKey, actionLabel, code,
                            supplierName,
                            timeStr, epoch,
                            // ADJUST không có note
                            actionKey.equals("ADJUST") ? "" :
                                    firstNonBlank(batchNote, stripBatchPrefix(log.getReason())),
                            log.getIngredient().getName(),
                            log.getIngredient().getUnit(),
                            log.getQuantity().abs()
                    )));
        }

        // ── 2. Orders (SALE — xuất bán) ──────────────────────────
        List<Order> orders =
                orderRepository.findCompletedWithIngredientsBetween(fromTs, toTs);

        for (Order order : orders) {
            long   epoch   = order.getCreatedAt() != null ? order.getCreatedAt() : 0L;
            String timeStr = toTimeStr(epoch);
            String code    = "[XUẤT BÁN]  " + order.getOrderCode();

            List<OrderItemIngredient> oiis =
                    orderItemIngredientRepository.findByOrderId(order.getId());

            Map<Long, AggIngredient> aggMap = new LinkedHashMap<>();
            for (OrderItemIngredient oii : oiis) {
                aggMap.merge(
                        oii.getIngredientId(),
                        new AggIngredient(oii.getIngredientName(),
                                oii.getUnit(), oii.getQuantityUsed()),
                        (a, b) -> new AggIngredient(
                                a.name(), a.unit(), a.qty().add(b.qty()))
                );
            }

            aggMap.values().stream()
                    .filter(agg -> agg.qty().compareTo(BigDecimal.ZERO) != 0)
                    .sorted(Comparator.comparing(AggIngredient::name))
                    .forEach(agg -> result.add(new ReportRow(
                            "SALE", "XUẤT BÁN",
                            code,
                            "",     // không hiển thị NCC cho xuất bán
                            timeStr, epoch,
                            "",     // không có note cho xuất bán
                            agg.name(), agg.unit(), agg.qty()
                    )));
        }

        result.sort(Comparator.comparingLong(ReportRow::epochMs)
                .thenComparing(ReportRow::batchCode));

        return result;
    }

    // ════════════════════════════════════════════════════════════
    // WRITE DATA ROWS
    // ════════════════════════════════════════════════════════════
    private void writeDataRows(XSSFSheet ws, StyleCache sc,
                               List<ReportRow> rows, int startRow) {
        int curRow = startRow;
        int stt    = 0;
        int i      = 0;

        while (i < rows.size()) {
            String batchCode = rows.get(i).batchCode();

            int j = i;
            while (j < rows.size() && rows.get(j).batchCode().equals(batchCode)) j++;
            List<ReportRow> group = rows.subList(i, j);
            int n = group.size();
            stt++;

            ReportRow first  = group.get(0);
            String    rowBg  = ACTION_BG.getOrDefault(first.actionKey(), ROW_ODD);
            String    labelFg = ACTION_LABEL_FG.getOrDefault(first.actionLabel(), "000000");
            String    qtyFg  = ACTION_QTY_FG.getOrDefault(first.actionKey(), "000000");

            for (int k = 0; k < n; k++) {
                ReportRow rr      = group.get(k);
                Row       row     = ws.createRow(curRow + k);
                boolean   isFirst = (k == 0);
                row.setHeightInPoints(20);

                // Col 0 — STT (merge)
                if (isFirst) {
                    Cell c = row.createCell(0);
                    c.setCellValue(stt);
                    c.setCellStyle(sc.merged(rowBg, "1E3A5F", true));
                    if (n > 1) ws.addMergedRegion(
                            new CellRangeAddress(curRow, curRow + n - 1, 0, 0));
                } else {
                    row.createCell(0).setCellStyle(sc.merged(rowBg, "1E3A5F", true));
                }

                // Col 1 — Nhà cung cấp (merge)
                if (isFirst) {
                    Cell c = row.createCell(1);
                    c.setCellValue(rr.supplier());
                    c.setCellStyle(sc.text(rowBg, "1A237E", false, true));
                    if (n > 1) ws.addMergedRegion(
                            new CellRangeAddress(curRow, curRow + n - 1, 1, 1));
                } else {
                    row.createCell(1).setCellStyle(sc.text(rowBg, "1A237E", false, true));
                }

                // Col 2 — Mã phiếu (merge)
                if (isFirst) {
                    Cell c = row.createCell(2);
                    c.setCellValue(rr.batchCode());
                    c.setCellStyle(sc.text(rowBg, labelFg, true, false));
                    if (n > 1) ws.addMergedRegion(
                            new CellRangeAddress(curRow, curRow + n - 1, 2, 2));
                } else {
                    row.createCell(2).setCellStyle(sc.text(rowBg, labelFg, true, false));
                }

                // Col 3 — Thời gian (merge)
                if (isFirst) {
                    Cell c = row.createCell(3);
                    c.setCellValue(rr.timeStr());
                    c.setCellStyle(sc.center(rowBg, "37474F", false));
                    if (n > 1) ws.addMergedRegion(
                            new CellRangeAddress(curRow, curRow + n - 1, 3, 3));
                } else {
                    row.createCell(3).setCellStyle(sc.center(rowBg, "37474F", false));
                }

                // Col 4 — Note (merge) ← MỚI
                if (isFirst) {
                    Cell c = row.createCell(4);
                    c.setCellValue(rr.note());
                    c.setCellStyle(sc.text(rowBg, "607D8B", false, true));
                    if (n > 1) ws.addMergedRegion(
                            new CellRangeAddress(curRow, curRow + n - 1, 4, 4));
                } else {
                    row.createCell(4).setCellStyle(sc.text(rowBg, "607D8B", false, true));
                }

                // Col 5 — Tên nguyên liệu
                Cell cIng = row.createCell(5);
                cIng.setCellValue(rr.ingredientName());
                cIng.setCellStyle(sc.text(rowBg, "212121", false, false));

                // Col 6 — ĐVT
                Cell cUnit = row.createCell(6);
                cUnit.setCellValue(rr.unit());
                cUnit.setCellStyle(sc.center(rowBg, "37474F", false));

                // Col 7 — Số lượng
                Cell cQty = row.createCell(7);
                cQty.setCellValue(rr.qty().doubleValue());
                cQty.setCellStyle(sc.number(rowBg, qtyFg));
            }

            curRow += n;
            i = j;
        }
    }

    // ════════════════════════════════════════════════════════════
    // STOCK TABLE (col 9, 10, 11 — không có total row)
    // ════════════════════════════════════════════════════════════
    private void buildStockTable(XSSFSheet ws, StyleCache sc,
                                 List<Ingredient> ingredients) {
        int startRow = 4;
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ing = ingredients.get(i);
            int r   = startRow + i;
            Row row = ws.getRow(r);
            if (row == null) row = ws.createRow(r);
            if (row.getHeightInPoints() < 20) row.setHeightInPoints(20);

            String bg = i % 2 == 0 ? ROW_ODD : ROW_EVEN;

            Cell cName = row.createCell(9);
            cName.setCellValue(ing.getName());
            cName.setCellStyle(sc.text(bg, "212121", false, false));

            Cell cUnit = row.createCell(10);
            cUnit.setCellValue(ing.getUnit());
            cUnit.setCellStyle(sc.center(bg, "37474F", false));

            Cell cQty = row.createCell(11);
            BigDecimal stock = ing.getStockQuantity();
            cQty.setCellValue(stock != null ? stock.doubleValue() : 0);
            cQty.setCellStyle(sc.number(bg, "E65100"));
        }
    }

    // ════════════════════════════════════════════════════════════
    // HEADER BUILDERS
    // ════════════════════════════════════════════════════════════
    private void buildTitleRow(XSSFSheet ws, StyleCache sc, int rowIdx,
                               String leftTitle, String rightTitle) {
        Row row = ws.createRow(rowIdx);
        row.setHeightInPoints(36);

        // Left table: col 0–7
        Cell c = row.createCell(0);
        c.setCellValue(leftTitle);
        c.setCellStyle(sc.mainTitle());
        ws.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 0, 7));

        row.createCell(8); // gap

        // Right table: col 9–11
        Cell r = row.createCell(9);
        r.setCellValue(rightTitle);
        r.setCellStyle(sc.stockTitle());
        ws.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 9, 11));
    }

    private void buildSubtitleRow(XSSFSheet ws, StyleCache sc, int rowIdx,
                                  String subtitle) {
        Row row = ws.createRow(rowIdx);
        row.setHeightInPoints(22);

        Cell c = row.createCell(0);
        c.setCellValue(subtitle);
        c.setCellStyle(sc.subTitle());
        ws.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 0, 7));

        Cell r = row.createCell(9);
        r.setCellValue("Cập nhật: " + LocalDateTime.now(VN).format(DT_FMT));
        r.setCellStyle(sc.stockSubtitle());
        ws.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 9, 11));
    }

    private void buildSpacerRow(XSSFSheet ws, StyleCache sc, int rowIdx) {
        Row row = ws.createRow(rowIdx);
        row.setHeightInPoints(10);
        for (int col = 0; col < 8; col++)
            row.createCell(col).setCellStyle(sc.spacer());
    }

    private void buildColumnHeaderRow(XSSFSheet ws, StyleCache sc, int rowIdx) {
        Row row = ws.createRow(rowIdx);
        row.setHeightInPoints(18);

        // Left table — 8 cols (thêm Note ở col 4)
        String[] leftHeaders = {
                "STT", "Nhà cung cấp", "Mã phiếu", "Thời gian", "Ghi chú",
                "Tên nguyên liệu", "Đơn vị tính", "Số lượng"
        };
        for (int i = 0; i < leftHeaders.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(leftHeaders[i]);
            cell.setCellStyle(sc.colHeader());
        }

        // Right table — col 9, 10, 11
        String[] rightHeaders = {"Tên nguyên liệu", "Đơn vị tính", "Số lượng tồn"};
        for (int i = 0; i < rightHeaders.length; i++) {
            Cell cell = row.createCell(9 + i);
            cell.setCellValue(rightHeaders[i]);
            cell.setCellStyle(sc.stockColHeader());
        }
    }

    // ════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════
    private String toTimeStr(long epochMillis) {
        if (epochMillis == 0) return "";
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis), VN).format(DT_FMT);
    }

    private String stripBatchPrefix(String reason) {
        if (reason == null) return "";
        int pipe = reason.indexOf('|');
        return (pipe >= 0 && pipe < reason.length() - 1)
                ? reason.substring(pipe + 1).trim() : reason;
    }

    private String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }

    // ════════════════════════════════════════════════════════════
    // STYLE CACHE
    // ════════════════════════════════════════════════════════════
    private static class StyleCache {
        private final XSSFWorkbook wb;
        private final Map<String, XSSFCellStyle> cache = new HashMap<>();

        StyleCache(XSSFWorkbook wb) { this.wb = wb; }

        private XSSFColor color(String hex) {
            return new XSSFColor(new byte[]{
                    (byte) Integer.parseInt(hex.substring(0, 2), 16),
                    (byte) Integer.parseInt(hex.substring(2, 4), 16),
                    (byte) Integer.parseInt(hex.substring(4, 6), 16),
            }, null);
        }

        private XSSFCellStyle base() {
            XSSFCellStyle s = wb.createCellStyle();
            XSSFFont f = wb.createFont();
            f.setFontName("Arial");
            f.setFontHeightInPoints((short) 9);
            s.setFont(f);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
            XSSFColor bc = color("B0BEC5");
            s.setTopBorderColor(bc);
            s.setBottomBorderColor(bc);
            s.setLeftBorderColor(bc);
            s.setRightBorderColor(bc);
            return s;
        }

        XSSFCellStyle mainTitle() {
            return cache.computeIfAbsent("mainTitle", k -> {
                XSSFCellStyle s = base();
                s.setFillForegroundColor(color(HEADER_BG));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont f = wb.createFont();
                f.setFontName("Arial"); f.setBold(true);
                f.setFontHeightInPoints((short) 16);
                f.setColor(color(HEADER_FG));
                s.setFont(f);
                s.setAlignment(HorizontalAlignment.CENTER);
                boldBorder(s);
                return s;
            });
        }

        XSSFCellStyle subTitle() {
            return cache.computeIfAbsent("subTitle", k -> {
                XSSFCellStyle s = base();
                s.setFillForegroundColor(color(SUB_BG));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont f = wb.createFont();
                f.setFontName("Arial"); f.setItalic(true);
                f.setFontHeightInPoints((short) 11);
                f.setColor(color(HEADER_FG));
                s.setFont(f);
                s.setAlignment(HorizontalAlignment.CENTER);
                boldBorder(s);
                return s;
            });
        }

        XSSFCellStyle stockTitle() {
            return cache.computeIfAbsent("stockTitle", k -> {
                XSSFCellStyle s = base();
                s.setFillForegroundColor(color(STOCK_HEAD));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont f = wb.createFont();
                f.setFontName("Arial"); f.setBold(true);
                f.setFontHeightInPoints((short) 13);
                f.setColor(color(HEADER_FG));
                s.setFont(f);
                s.setAlignment(HorizontalAlignment.CENTER);
                boldBorder(s);
                return s;
            });
        }

        XSSFCellStyle stockSubtitle() {
            return cache.computeIfAbsent("stockSubtitle", k -> {
                XSSFCellStyle s = base();
                s.setFillForegroundColor(color(STOCK_BG));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont f = wb.createFont();
                f.setFontName("Arial"); f.setItalic(true);
                f.setFontHeightInPoints((short) 9);
                f.setColor(color("5D4037"));
                s.setFont(f);
                s.setAlignment(HorizontalAlignment.CENTER);
                return s;
            });
        }

        XSSFCellStyle spacer() {
            return cache.computeIfAbsent("spacer", k -> {
                XSSFCellStyle s = base();
                s.setFillForegroundColor(color("EBF5FB"));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                return s;
            });
        }

        XSSFCellStyle colHeader() {
            return cache.computeIfAbsent("colHeader", k -> {
                XSSFCellStyle s = base();
                s.setFillForegroundColor(color(COL_HEAD));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont f = wb.createFont();
                f.setFontName("Arial"); f.setBold(true);
                f.setFontHeightInPoints((short) 10);
                f.setColor(color(COL_HEAD_FG));
                s.setFont(f);
                s.setAlignment(HorizontalAlignment.CENTER);
                return s;
            });
        }

        XSSFCellStyle stockColHeader() {
            return cache.computeIfAbsent("stockColHeader", k -> {
                XSSFCellStyle s = base();
                s.setFillForegroundColor(color(STOCK_HEAD));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont f = wb.createFont();
                f.setFontName("Arial"); f.setBold(true);
                f.setFontHeightInPoints((short) 10);
                f.setColor(color(HEADER_FG));
                s.setFont(f);
                s.setAlignment(HorizontalAlignment.CENTER);
                return s;
            });
        }

        XSSFCellStyle text(String bg, String fg, boolean bold, boolean wrap) {
            String key = "t_" + bg + fg + bold + wrap;
            return cache.computeIfAbsent(key, k -> {
                XSSFCellStyle s = base();
                s.setFillForegroundColor(color(bg));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont f = wb.createFont();
                f.setFontName("Arial"); f.setBold(bold);
                f.setFontHeightInPoints((short) 9);
                f.setColor(color(fg));
                s.setFont(f);
                s.setWrapText(wrap);
                s.setAlignment(HorizontalAlignment.LEFT);
                return s;
            });
        }

        XSSFCellStyle center(String bg, String fg, boolean bold) {
            String key = "c_" + bg + fg + bold;
            return cache.computeIfAbsent(key, k -> {
                XSSFCellStyle s = base();
                s.setFillForegroundColor(color(bg));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont f = wb.createFont();
                f.setFontName("Arial"); f.setBold(bold);
                f.setFontHeightInPoints((short) 9);
                f.setColor(color(fg));
                s.setFont(f);
                s.setAlignment(HorizontalAlignment.CENTER);
                return s;
            });
        }

        XSSFCellStyle number(String bg, String fg) {
            String key = "n_" + bg + fg;
            return cache.computeIfAbsent(key, k -> {
                XSSFCellStyle s = base();
                s.setFillForegroundColor(color(bg));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont f = wb.createFont();
                f.setFontName("Arial"); f.setBold(true);
                f.setFontHeightInPoints((short) 9);
                f.setColor(color(fg));
                s.setFont(f);
                s.setAlignment(HorizontalAlignment.RIGHT);
                s.setDataFormat(wb.createDataFormat().getFormat("#,##0.##"));
                return s;
            });
        }

        XSSFCellStyle merged(String bg, String fg, boolean bold) {
            String key = "m_" + bg + fg + bold;
            return cache.computeIfAbsent(key, k -> {
                XSSFCellStyle s = base();
                s.setFillForegroundColor(color(bg));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont f = wb.createFont();
                f.setFontName("Arial"); f.setBold(bold);
                f.setFontHeightInPoints((short) 9);
                f.setColor(color(fg));
                s.setFont(f);
                s.setAlignment(HorizontalAlignment.CENTER);
                return s;
            });
        }

        private void boldBorder(XSSFCellStyle s) {
            s.setBorderTop(BorderStyle.MEDIUM);
            s.setBorderBottom(BorderStyle.MEDIUM);
            s.setBorderLeft(BorderStyle.MEDIUM);
            s.setBorderRight(BorderStyle.MEDIUM);
        }
    }
}