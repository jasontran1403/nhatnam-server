package com.nhatnam.server.einvoice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.einvoice.ViettelEInvoiceConfig;
import com.nhatnam.server.einvoice.dto.EInvoiceRequestDto.BusinessBuyerInfo;
import com.nhatnam.server.einvoice.dto.EInvoiceRequestDto.EInvoiceResult;
import com.nhatnam.server.einvoice.dto.ViettelInvoiceDto;
import com.nhatnam.server.einvoice.dto.ViettelInvoiceDto.*;
import com.nhatnam.server.entity.pos.PosOrder;
import com.nhatnam.server.entity.pos.PosOrderItem;
import com.nhatnam.server.util.NumberToWordsVN;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Log4j2
public class ViettelEInvoiceService {

    private final ViettelEInvoiceConfig config;
    private final RestTemplate          restTemplate;
    private final ObjectMapper          objectMapper;

    private final AtomicReference<String> cachedToken    = new AtomicReference<>();
    private final AtomicLong              tokenExpiresAt = new AtomicLong(0);

    private final UsbTokenSigner usbTokenSigner;

    public ViettelEInvoiceService(ViettelEInvoiceConfig config,
                                  RestTemplate restTemplate,
                                  ObjectMapper objectMapper,
                                  UsbTokenSigner usbTokenSigner) {
        this.config         = config;
        this.restTemplate   = restTemplate;
        this.objectMapper   = objectMapper;
        this.usbTokenSigner = usbTokenSigner;
    }

    // ════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════

    public EInvoiceResult createRetailInvoice(PosOrder order) {
        BuyerInfo buyer = BuyerInfo.builder()
                .buyerName("Khách lẻ")
                .buyerNotGetInvoice("1")
                .build();
        CreateInvoiceRequest req = buildInvoiceRequest(order, buyer);
        return "USB_TOKEN".equals(config.getSignMode())
                ? issueInvoiceUsbToken(req)
                : issueInvoice(req, false);
    }

    public EInvoiceResult createBusinessInvoice(PosOrder order, BusinessBuyerInfo buyerInfo) {
        BuyerInfo buyer = BuyerInfo.builder()
                .buyerLegalName(buyerInfo.getCompanyName())
                .buyerTaxCode(buyerInfo.getTaxCode())
                .buyerAddressLine(buyerInfo.getAddress())   // frontend /business/{code} phải truyền đủ
                .buyerEmail(buyerInfo.getEmail())
                .buyerPhoneNumber(buyerInfo.getPhone())
                .buyerNotGetInvoice("0")
                .build();
        CreateInvoiceRequest req = buildInvoiceRequest(order, buyer);
        return "USB_TOKEN".equals(config.getSignMode())
                ? issueInvoiceUsbToken(req)
                : issueInvoice(req, false);
    }

    public EInvoiceResult createDraftInvoice(PosOrder order, BuyerInfo buyer) {
        return issueInvoice(buildInvoiceRequest(order, buyer), true);
    }

    // ────────────────────────────────────────────────────────────────
    // Entry point dùng chung — nhận sẵn CreateInvoiceRequest.
    // Cho phép nguồn khác (đơn sỉ/lẻ) tự build request rồi tái sử dụng
    // toàn bộ phần ký số / gọi API / xử lý lỗi ở đây.
    // ────────────────────────────────────────────────────────────────

    /** Phát hành hóa đơn từ request đã dựng sẵn (tự chọn HSM / USB token). */
    public EInvoiceResult issuePreparedInvoice(CreateInvoiceRequest req) {
        return "USB_TOKEN".equals(config.getSignMode())
                ? issueInvoiceUsbToken(req)
                : issueInvoice(req, false);
    }

    /** Xem trước PDF (base64) từ request đã dựng sẵn. */
    public String previewPreparedInvoice(CreateInvoiceRequest req) {
        String url = config.getBaseUrl()
                + "/InvoiceAPI/InvoiceUtilsWS/createInvoiceDraftPreview/"
                + config.getSupplierTaxCode();
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(req, buildHeaders()), String.class);
            JsonNode node = objectMapper.readTree(resp.getBody());
            if (node.has("fileToBytes")) return node.get("fileToBytes").asText();
            return resp.getBody();

        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // Viettel trả 4xx/5xx kèm body JSON mô tả lỗi → dịch sang tiếng Việt
            throw ViettelErrorTranslator.translate(
                    e.getResponseBodyAsString(), req.getGeneralInvoiceInfo(),
                    e.getStatusCode().value(), objectMapper);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw ViettelErrorTranslator.unreachable(e);
        } catch (Exception e) {
            log.error("[EInvoice] previewPreparedInvoice lỗi không xác định: {}", e.getMessage(), e);
            throw new com.nhatnam.server.einvoice.EInvoiceException(
                    com.nhatnam.server.einvoice.EInvoiceException.Kind.UNKNOWN,
                    "Không tạo được bản xem trước hóa đơn: " + e.getMessage(), null);
        }
    }

    /** Cấu hình dùng chung cho các service build request khác. */
    public ViettelEInvoiceConfig getConfig() {
        return config;
    }

    public String getInvoicePdfBase64(String invoiceNo) {
        return getInvoicePdfBase64(invoiceNo, null);
    }

    /**
     * @param templateCode mẫu số của chính hóa đơn đó. Bắt buộc truyền khi hệ thống
     *                     dùng nhiều dải (POS máy tính tiền vs bán sỉ/lẻ) — truyền sai
     *                     mẫu số thì Viettel không tìm thấy file. null → dùng mặc định.
     */
    public String getInvoicePdfBase64(String invoiceNo, String templateCode) {
        String url = config.getBaseUrl()
                + "/InvoiceAPI/InvoiceUtilsWS/getInvoiceRepresentationFile";
        GetFileRequest body = GetFileRequest.builder()
                .supplierTaxCode(config.getSupplierTaxCode())
                .invoiceNo(invoiceNo)
                .templateCode(templateCode != null && !templateCode.isBlank()
                        ? templateCode : config.getTemplateCode())
                .fileType("PDF")
                .build();
        try {
            ResponseEntity<GetFileResponse> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, buildHeaders()), GetFileResponse.class);
            GetFileResponse data = resp.getBody();
            if (data == null || data.getFileToBytes() == null) {
                log.warn("[EInvoice] getInvoicePdf: response rỗng invoiceNo={}", invoiceNo);
                return null;
            }
            return data.getFileToBytes();
        } catch (HttpClientErrorException e) {
            var ex = ViettelErrorTranslator.translate(
                    e.getResponseBodyAsString(), null, e.getStatusCode().value(), objectMapper);
            log.warn("[EInvoice] Không lấy được PDF ({}): {} | raw={}",
                    ex.getKind(), ex.getMessage(), ex.getRawDetail());
            throw ex;
        }
    }

    /**
     * Xem trước PDF hóa đơn nháp (createInvoiceDraftPreview).
     * Trả về base64 PDF — không phát hành, không lưu DB.
     */
    public String previewInvoice(PosOrder order, BuyerInfo buyer) {
        return previewPreparedInvoice(buildInvoiceRequest(order, buyer));
    }

    /**
     * Gửi hóa đơn lên cơ quan thuế (CQT) bằng transactionUuid.
     */
    public Map<String, Object> sendToCqt(String invoiceNo, String transactionId) {
        String url = config.getBaseUrl()
                + "/InvoiceAPI/InvoiceWS/sendInvoiceByTransactionUuid";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supplierTaxCode", config.getSupplierTaxCode());
        body.put("transactionUuid", transactionId);
        body.put("invoiceNo", invoiceNo);
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, buildHeaders()), String.class);
            log.info("[EInvoice] sendToCqt response: {}", resp.getBody());
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(resp.getBody());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("invoiceNo", invoiceNo);
            result.put("response", resp.getBody());
            return result;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            var ex = ViettelErrorTranslator.translate(
                    e.getResponseBodyAsString(), null, e.getStatusCode().value(), objectMapper);
            log.warn("[EInvoice] Gửi CQT bị từ chối ({}): {} | raw={}",
                    ex.getKind(), ex.getMessage(), ex.getRawDetail());
            throw ex;
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw ViettelErrorTranslator.unreachable(e);
        } catch (Exception e) {
            log.error("[EInvoice] sendToCqt lỗi không xác định: {}", e.getMessage(), e);
            throw new com.nhatnam.server.einvoice.EInvoiceException(
                    com.nhatnam.server.einvoice.EInvoiceException.Kind.UNKNOWN,
                    "Không gửi được lên cơ quan thuế: " + e.getMessage(), null);
        }
    }

    public String getInvoiceXmlBase64(String invoiceNo) {
        return getInvoiceXmlBase64(invoiceNo, null);
    }

    public String getInvoiceXmlBase64(String invoiceNo, String templateCode) {
        String url = config.getBaseUrl()
                + "/InvoiceAPI/InvoiceUtilsWS/getInvoiceRepresentationFile";
        GetFileRequest body = GetFileRequest.builder()
                .supplierTaxCode(config.getSupplierTaxCode())
                .invoiceNo(invoiceNo)
                .templateCode(templateCode != null && !templateCode.isBlank()
                        ? templateCode : config.getTemplateCode())
                .fileType("XML")
                .build();
        ResponseEntity<GetFileResponse> resp = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, buildHeaders()), GetFileResponse.class);
        return Objects.requireNonNull(resp.getBody()).getFileToBytes();
    }

    public byte[] decodePdfBytes(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    // ════════════════════════════════════════════════════════════════
    // PRIVATE — Auth (OAuth2)
    // ════════════════════════════════════════════════════════════════

    private String getAccessToken() {
        long now = System.currentTimeMillis();
        if (cachedToken.get() != null && now < tokenExpiresAt.get() - 60_000L) {
            return cachedToken.get();
        }
        return login();
    }

    private String login() {
        log.info("[EInvoice] Login Viettel: username={}", config.getUsername());

        // Viettel /auth/login: POST với body JSON { "username": "...", "password": "..." }
        // Content-Type: application/json (KHÔNG dùng Basic Auth cho endpoint này)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("username", config.getUsername());
        body.put("password", config.getPassword());

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    config.getAuthUrl(), HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);

            JsonNode json  = objectMapper.readTree(resp.getBody());
            String   token = json.path("access_token").asText(null);

            if (token == null || token.isBlank()) {
                throw new RuntimeException("Viettel login không trả về access_token: " + resp.getBody());
            }

            long expiresIn = json.path("expires_in").asLong(1200);
            cachedToken.set(token);
            tokenExpiresAt.set(System.currentTimeMillis() + expiresIn * 1000L);

            log.info("[EInvoice] Login thành công, hết hạn sau {}s", expiresIn);
            return token;

        } catch (HttpClientErrorException e) {
            log.error("[EInvoice] Login thất bại HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Viettel login thất bại: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("[EInvoice] Login error: {}", e.getMessage(), e);
            throw new RuntimeException("Viettel login error: " + e.getMessage());
        }
    }

    /**
     * Postman collection dùng Basic Auth cho tất cả invoice endpoints.
     * /auth/login chỉ dùng để lấy token nếu cần (hiện không dùng Bearer).
     */
    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String creds   = config.getUsername() + ":" + config.getPassword();
        String encoded = Base64.getEncoder()
                .encodeToString(creds.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + encoded);
        return headers;
    }

    // ════════════════════════════════════════════════════════════════
    // PRIVATE — Build request
    // ════════════════════════════════════════════════════════════════

    private CreateInvoiceRequest buildInvoiceRequest(PosOrder order, BuyerInfo buyer) {
        // Đơn POS → dải hóa đơn máy tính tiền (nếu đã cấu hình pos-* trong yml)
        GeneralInvoiceInfo generalInfo = GeneralInvoiceInfo.builder()
                .invoiceType(config.invoiceTypeFor(true))
                .templateCode(config.templateCodeFor(true))
                .invoiceSeries(config.invoiceSeriesFor(true))
                .currencyCode("VND")
                .exchangeRate(1)
                .adjustmentType("1")
                .paymentStatus(true)
                .cusGetInvoiceRight(true)  // Viettel test env yêu cầu true; prod có thể đổi lại
                .invoiceIssuedDate(null)
                .transactionUuid(null)  // null = Viettel bỏ qua idempotency check
                .build();

        List<PaymentMethod> payments = List.of(
                PaymentMethod.builder()
                        .paymentMethod(mapPaymentMethodCode(order.getPaymentMethod()))
                        .paymentMethodName(mapPaymentMethod(order.getPaymentMethod()))
                        .build());

        return CreateInvoiceRequest.builder()
                .generalInvoiceInfo(generalInfo)
                .buyerInfo(buyer)
                .payments(payments)
                .itemInfo(buildItemInfoList(order))
                .taxBreakdowns(buildTaxBreakdowns(order))
                .summarizeInfo(buildSummarizeInfo(order))
                .metadata(List.of(Metadata.builder()
                        .keyTag("invoiceNote")
                        .stringValue(order.getNote() != null ? order.getNote() : "")
                        .valueType("text")
                        .keyLabel("Ghi chú")
                        .build()))
                .build();
    }

    // ════════════════════════════════════════════════════════════════
    // DÒNG HÀNG HÓA — nguồn dữ liệu CHUNG cho itemInfo / summarize / taxBreakdown
    //
    // Mỗi món 1 dòng, và MỖI ADDON 1 DÒNG RIÊNG.
    // Trước đây addon không xuất hiện trên hóa đơn, trong khi
    // order.finalAmount lại đã bao gồm tiền addon → tổng hóa đơn không khớp
    // tổng các dòng. Gom về 1 chỗ để 3 khối luôn nhất quán với nhau.
    // ════════════════════════════════════════════════════════════════

    /**
     * 1 dòng trên hóa đơn.
     * @param priceWithTax đơn giá ĐÃ gồm VAT (giá POS là giá inclusive)
     */
    private record InvLine(String name, BigDecimal quantity,
                           BigDecimal priceWithTax, int taxPct) {}

    /** Kết quả bóc VAT của 1 dòng. */
    private record LineAmounts(BigDecimal unitPriceNoTax, BigDecimal amountNoTax,
                               BigDecimal amountWithTax, BigDecimal taxAmount) {}

    /**
     * Bóc VAT ngược từ đơn giá đã gồm thuế.
     *   unitPriceNoTax = priceWithTax / (1 + taxPct/100)   (làm tròn đến đồng)
     *   taxAmount      = amountWithTax - amountNoTax
     */
    private LineAmounts calcLineAmounts(BigDecimal priceWithTax, BigDecimal qty, int taxPct) {
        BigDecimal price = priceWithTax != null ? priceWithTax : BigDecimal.ZERO;

        if (taxPct <= 0) {
            BigDecimal amount = price.multiply(qty);
            return new LineAmounts(price, amount, amount, BigDecimal.ZERO);
        }

        BigDecimal unitNoTax = price.divide(
                BigDecimal.ONE.add(BigDecimal.valueOf(taxPct).divide(BigDecimal.valueOf(100))),
                0, java.math.RoundingMode.HALF_UP);
        BigDecimal amountNoTax   = unitNoTax.multiply(qty);
        BigDecimal amountWithTax = price.multiply(qty);
        return new LineAmounts(unitNoTax, amountNoTax, amountWithTax,
                amountWithTax.subtract(amountNoTax));
    }

    /**
     * Dựng toàn bộ dòng hàng của đơn: món chính + các addon của nó.
     *
     * Addon dùng {@code addonPriceNet} (giá quán thực nhận) cho khớp với
     * {@code finalUnitPrice} của món chính — cả hai đều là giá net.
     * Addon ăn theo thuế suất của món cha, không có thuế suất riêng.
     * Addon trùng tên VÀ trùng giá trong cùng một món thì gộp làm 1 dòng.
     */
    private List<InvLine> buildInvoiceLines(PosOrder order) {
        List<InvLine> lines = new ArrayList<>();

        for (PosOrderItem poi : order.getItems()) {
            int taxPct = poi.getVatPercent() != null
                    ? poi.getVatPercent() : config.getDefaultTaxPercent();

            lines.add(new InvLine(
                    poi.getProductName(),
                    BigDecimal.valueOf(poi.getQuantity()),
                    poi.getFinalUnitPrice(),
                    taxPct));

            lines.addAll(buildAddonLines(poi, taxPct));
        }

        return lines;
    }

    /** Các dòng addon của 1 món. Rỗng nếu món không có addon. */
    private List<InvLine> buildAddonLines(PosOrderItem poi, int taxPct) {
        if (poi.getSelectedIngredients() == null || poi.getSelectedIngredients().isEmpty())
            return Collections.emptyList();

        // key = tên + giá → cùng tên khác giá vẫn tách dòng (giá addon có thể
        // được set riêng theo từng món). value = [số lượng, đơn giá]
        Map<String, BigDecimal[]> merged = new LinkedHashMap<>();

        for (var si : poi.getSelectedIngredients()) {
            // Chỉ nguyên liệu thuộc nhóm addon mới có addonPriceSnapshot
            if (si.getAddonPriceSnapshot() == null) continue;

            BigDecimal price = si.getAddonPriceNet() != null
                    ? si.getAddonPriceNet() : si.getAddonPriceSnapshot();
            if (price == null || price.signum() <= 0) continue;   // addon tặng kèm → không lên hóa đơn

            int count = si.getSelectedCount() != null ? si.getSelectedCount() : 0;
            if (count <= 0) continue;

            String name = si.getIngredientName() != null
                    ? si.getIngredientName().trim() : "Món thêm";

            String key = name + "|" + price.stripTrailingZeros().toPlainString();
            BigDecimal[] agg = merged.computeIfAbsent(key,
                    k -> new BigDecimal[]{BigDecimal.ZERO, price});
            agg[0] = agg[0].add(BigDecimal.valueOf(count));
        }

        List<InvLine> result = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : merged.entrySet()) {
            String name = e.getKey().substring(0, e.getKey().lastIndexOf('|'));
            // Ghi rõ addon thuộc món nào — hóa đơn có thể có "Trứng" của
            // nhiều món khác nhau với giá khác nhau.
            String lineName = name + " (món thêm của " + poi.getProductName() + ")";
            result.add(new InvLine(lineName, e.getValue()[0], e.getValue()[1], taxPct));
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════

    private List<ItemInfo> buildItemInfoList(PosOrder order) {
        List<ItemInfo> items = new ArrayList<>();
        int line = 1;

        for (InvLine l : buildInvoiceLines(order)) {
            LineAmounts a = calcLineAmounts(l.priceWithTax(), l.quantity(), l.taxPct());

            items.add(ItemInfo.builder()
                    .lineNumber(line++)
                    .selection(1)
                    .itemName(l.name())
                    .unitName("phần")
                    .quantity(l.quantity())
                    .unitPrice(a.unitPriceNoTax())
                    .itemTotalAmountWithoutTax(a.amountNoTax())
                    .itemTotalAmountAfterDiscount(a.amountNoTax())
                    .itemTotalAmountWithTax(a.amountWithTax())
                    .taxPercentage(l.taxPct() <= 0 ? -2 : l.taxPct())
                    .taxAmount(a.taxAmount())
                    .isIncreaseItem(null)
                    .build());
        }

        if (order.getDiscountAmount() != null
                && order.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            items.add(ItemInfo.builder()
                    .lineNumber(line)
                    .selection(3)
                    .itemName(order.getDiscountNote() != null ? order.getDiscountNote() : "Chiết khấu")
                    .itemTotalAmountWithoutTax(order.getDiscountAmount())
                    .itemTotalAmountAfterDiscount(order.getDiscountAmount())
                    .itemTotalAmountWithTax(order.getDiscountAmount())
                    .taxPercentage(-2)
                    .taxAmount(BigDecimal.ZERO)
                    .isIncreaseItem(false)
                    .build());
        }

        return items;
    }

    private SummarizeInfo buildSummarizeInfo(PosOrder order) {
        BigDecimal totalNoTax = BigDecimal.ZERO;
        BigDecimal totalVat   = BigDecimal.ZERO;

        for (InvLine l : buildInvoiceLines(order)) {
            LineAmounts a = calcLineAmounts(l.priceWithTax(), l.quantity(), l.taxPct());
            totalNoTax = totalNoTax.add(a.amountNoTax());
            totalVat   = totalVat.add(a.taxAmount());
        }

        BigDecimal discountAmt   = order.getDiscountAmount() != null
                ? order.getDiscountAmount() : BigDecimal.ZERO;
        BigDecimal afterDiscount = totalNoTax.subtract(discountAmt);

        return SummarizeInfo.builder()
                .sumOfTotalLineAmountWithoutTax(totalNoTax)
                .totalAmountAfterDiscount(afterDiscount)
                .totalAmountWithoutTax(afterDiscount)
                .totalTaxAmount(totalVat)
                .totalAmountWithTax(order.getFinalAmount())
                .totalAmountWithTaxInWords(NumberToWordsVN.convert(order.getFinalAmount().longValue()))
                .discountAmount(discountAmt.compareTo(BigDecimal.ZERO) > 0 ? discountAmt : null)
                .build();
    }

    private List<TaxBreakdown> buildTaxBreakdowns(PosOrder order) {
        Map<Integer, BigDecimal[]> groups = new LinkedHashMap<>();

        for (InvLine l : buildInvoiceLines(order)) {
            int viettelPct = l.taxPct() <= 0 ? -2 : l.taxPct();
            LineAmounts a  = calcLineAmounts(l.priceWithTax(), l.quantity(), l.taxPct());
            groups.merge(viettelPct, new BigDecimal[]{a.amountNoTax(), a.taxAmount()},
                    (x, y) -> new BigDecimal[]{x[0].add(y[0]), x[1].add(y[1])});
        }

        List<TaxBreakdown> result = new ArrayList<>();
        groups.forEach((pct, v) -> result.add(TaxBreakdown.builder()
                .taxPercentage(pct).taxableAmount(v[0]).taxAmount(v[1]).build()));
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    // PRIVATE — HTTP call
    // ════════════════════════════════════════════════════════════════

    /**
     * Luồng USB token 3 bước:
     *   B1. createInvoiceUsbTokenGetHash    → nhận hashData + reservationCode
     *   B2. Ký hashData bằng USB token      → signatureBase64
     *   B3. createInvoiceUsbTokenInsertSignature → nhận invoiceNo
     */
    private EInvoiceResult issueInvoiceUsbToken(CreateInvoiceRequest req) {
        log.info("[EInvoice][USB] Bắt đầu luồng USB token");
        try {
            // ── Bước 1: GetHash ──────────────────────────────────
            String certSerial = config.getCertificateSerial() != null
                    ? config.getCertificateSerial()
                    : usbTokenSigner.getCertificateSerial();

            // Gắn certificateSerial + validation vào generalInvoiceInfo
            req.getGeneralInvoiceInfo().setCertificateSerial(certSerial);
            req.getGeneralInvoiceInfo().setValidation(0);

            String getHashUrl = config.getBaseUrl()
                    + "/InvoiceAPI/InvoiceWS/createInvoiceUsbTokenGetHash/"
                    + config.getSupplierTaxCode();

            log.info("[EInvoice][USB] B1 GetHash url={}", getHashUrl);
            try { log.info("[EInvoice][USB] B1 REQUEST: {}", objectMapper.writeValueAsString(req)); }
            catch (Exception ignore) {}

            ResponseEntity<String> hashResp = restTemplate.exchange(
                    getHashUrl, HttpMethod.POST,
                    new HttpEntity<>(req, buildHeaders()), String.class);
            log.info("[EInvoice][USB] B1 RESPONSE: {}", hashResp.getBody());

            ViettelInvoiceDto.GetHashResponse hashBody =
                    objectMapper.readValue(hashResp.getBody(), ViettelInvoiceDto.GetHashResponse.class);

            if (!hashBody.isSuccess()) {
                var ex = ViettelErrorTranslator.translate(
                        hashResp.getBody(), req.getGeneralInvoiceInfo(), 200, objectMapper);
                log.warn("[EInvoice][USB] GetHash bị từ chối ({}): {} | raw={}",
                        ex.getKind(), ex.getMessage(), ex.getRawDetail());
                return errorResult(ex.getMessage());
            }
            if (hashBody.getResult() == null || hashBody.getResult().getHashData() == null) {
                return errorResult("[GetHash] Không nhận được hashData từ Viettel");
            }

            String hashData        = hashBody.getResult().getHashData();
            String reservationCode = hashBody.getResult().getReservationCode();
            log.info("[EInvoice][USB] B1 OK hashData={} reservationCode={}", hashData, reservationCode);

            // ── Bước 2: Ký bằng USB token ────────────────────────
            log.info("[EInvoice][USB] B2 Ký bằng USB token...");
            String signature = usbTokenSigner.sign(hashData);
            log.info("[EInvoice][USB] B2 Ký xong, signature length={}", signature.length());

            // ── Bước 3: InsertSignature ──────────────────────────
            // Gắn reservationCode vào generalInvoiceInfo
            req.getGeneralInvoiceInfo().setReservationCode(reservationCode);
            req.getGeneralInvoiceInfo().setCertificateSerial(null); // không cần ở bước 3

            // Build body InsertSignature: thêm signatureValue
            Map<String, Object> insertBody = objectMapper.convertValue(req, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>(){});
            insertBody.put("signatureValue", signature);

            String insertUrl = config.getBaseUrl()
                    + "/InvoiceAPI/InvoiceWS/createInvoiceUsbTokenInsertSignature";
            log.info("[EInvoice][USB] B3 InsertSignature url={}", insertUrl);
            try { log.info("[EInvoice][USB] B3 REQUEST: {}", objectMapper.writeValueAsString(insertBody)); }
            catch (Exception ignore) {}

            ResponseEntity<String> insertResp = restTemplate.exchange(
                    insertUrl, HttpMethod.POST,
                    new HttpEntity<>(insertBody, buildHeaders()), String.class);
            log.info("[EInvoice][USB] B3 RESPONSE: {}", insertResp.getBody());

            ViettelInvoiceDto.CreateInvoiceResponse insertBodyParsed =
                    objectMapper.readValue(insertResp.getBody(), ViettelInvoiceDto.CreateInvoiceResponse.class);

            String errCode = insertBodyParsed.getErrorCode();
            boolean ok = errCode == null || errCode.isBlank() || "200".equals(errCode);
            if (!ok) {
                var ex = ViettelErrorTranslator.translate(
                        insertResp.getBody(), req.getGeneralInvoiceInfo(), 200, objectMapper);
                log.warn("[EInvoice][USB] InsertSignature bị từ chối ({}): {} | raw={}",
                        ex.getKind(), ex.getMessage(), ex.getRawDetail());
                return errorResult(ex.getMessage());
            }

            ViettelInvoiceDto.CreateInvoiceResponse.InvoiceResult vr = insertBodyParsed.getResult();
            String invoiceNo       = vr != null ? vr.getInvoiceNo()       : insertBodyParsed.getInvoiceNo();
            String resCode         = vr != null ? vr.getReservationCode() : null;
            String transactionID   = vr != null ? vr.getTransactionID()   : null;

            log.info("[EInvoice][USB] Hoàn tất invoiceNo={}", invoiceNo);
            EInvoiceResult r = new EInvoiceResult();
            r.setInvoiceNo(invoiceNo);
            r.setReservationCode(resCode);
            r.setTransactionID(transactionID);
            r.setTemplateCode(req.getGeneralInvoiceInfo().getTemplateCode());
            r.setInvoiceSeries(req.getGeneralInvoiceInfo().getInvoiceSeries());
            r.setStatus("ISSUED");
            return r;

        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            var ex = ViettelErrorTranslator.translate(
                    e.getResponseBodyAsString(), req.getGeneralInvoiceInfo(),
                    e.getStatusCode().value(), objectMapper);
            log.warn("[EInvoice][USB] Viettel từ chối ({}): {} | raw={}",
                    ex.getKind(), ex.getMessage(), ex.getRawDetail());
            return errorResult(ex.getMessage());
        } catch (org.springframework.web.client.ResourceAccessException e) {
            var ex = ViettelErrorTranslator.unreachable(e);
            log.warn("[EInvoice][USB] {}", ex.getMessage());
            return errorResult(ex.getMessage());
        } catch (Exception e) {
            log.error("[EInvoice][USB] Lỗi không xác định: {}", e.getMessage(), e);
            return errorResult("Lỗi ký USB token: " + e.getMessage());
        }
    }

    private EInvoiceResult issueInvoice(CreateInvoiceRequest req, boolean isDraft) {
        String endpoint = isDraft
                ? "/InvoiceAPI/InvoiceWS/createOrUpdateInvoiceDraft/"
                : "/InvoiceAPI/InvoiceWS/createInvoice/";
        String url = config.getBaseUrl() + endpoint + config.getSupplierTaxCode();
        log.info("[EInvoice] {} draft={}", endpoint, isDraft);

        log.info("Body: {}", req);
        try {
            // Log body thực tế để debug
            try { log.info("[EInvoice] REQUEST BODY: {}", objectMapper.writeValueAsString(req)); }
            catch (Exception ignore) {}

            // Lấy raw string trước để debug deserialization
            ResponseEntity<String> rawResp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(req, buildHeaders()),
                    String.class);
            log.info("[EInvoice] RAW RESPONSE: {}", rawResp.getBody());

            CreateInvoiceResponse body = objectMapper.readValue(rawResp.getBody(), CreateInvoiceResponse.class);
            if (body == null) return errorResult("Viettel trả về response rỗng");

            // Viettel trả errorCode=null hoặc errorCode="" hoặc errorCode="200" khi thành công
            String errCode = body.getErrorCode();
            boolean ok = errCode == null || errCode.isBlank() || "200".equals(errCode);

            if (!ok) {
                var ex = ViettelErrorTranslator.translate(
                        rawResp.getBody(), req.getGeneralInvoiceInfo(), 200, objectMapper);
                log.warn("[EInvoice] Viettel từ chối ({}): {} | raw={}",
                        ex.getKind(), ex.getMessage(), ex.getRawDetail());
                return errorResult(ex.getMessage());
            }

            // invoiceNo / reservationCode nằm trong result nested
            CreateInvoiceResponse.InvoiceResult vr = body.getResult();
            String invoiceNo       = vr != null ? vr.getInvoiceNo()       : body.getInvoiceNo();
            String reservationCode = vr != null ? vr.getReservationCode() : null;
            String transactionID   = vr != null ? vr.getTransactionID()   : null;

            log.info("[EInvoice] Thành công invoiceNo={} reservationCode={} transactionID={}",
                    invoiceNo, reservationCode, transactionID);

            EInvoiceResult r = new EInvoiceResult();
            r.setInvoiceNo(invoiceNo);
            r.setReservationCode(reservationCode);
            r.setTransactionID(transactionID);
            r.setInvoiceIssuedDate(body.getInvoiceIssuedDate());
            r.setTemplateCode(req.getGeneralInvoiceInfo().getTemplateCode());
            r.setInvoiceSeries(req.getGeneralInvoiceInfo().getInvoiceSeries());
            r.setStatus(isDraft ? "DRAFT" : "ISSUED");
            return r;

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401) {
                log.warn("[EInvoice] Token hết hạn, login lại...");
                cachedToken.set(null);
                tokenExpiresAt.set(0);
                return issueInvoice(req, isDraft);
            }
            var ex = ViettelErrorTranslator.translate(
                    e.getResponseBodyAsString(), req.getGeneralInvoiceInfo(),
                    e.getStatusCode().value(), objectMapper);
            log.warn("[EInvoice] Viettel từ chối ({}): {} | raw={}",
                    ex.getKind(), ex.getMessage(), ex.getRawDetail());
            return errorResult(ex.getMessage());
        } catch (org.springframework.web.client.ResourceAccessException e) {
            var ex = ViettelErrorTranslator.unreachable(e);
            log.warn("[EInvoice] {}", ex.getMessage());
            return errorResult(ex.getMessage());
        } catch (Exception e) {
            log.error("[EInvoice] Lỗi không xác định: {}", e.getMessage(), e);
            return errorResult(e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // PRIVATE — Helpers
    // ════════════════════════════════════════════════════════════════

    private EInvoiceResult errorResult(String message) {
        EInvoiceResult r = new EInvoiceResult();
        r.setStatus("ERROR");
        r.setErrorMessage(message);
        return r;
    }

    private String mapPaymentMethod(String m) {
        if (m == null) return "TM";
        return switch (m.toUpperCase()) {
            case "CASH"                      -> "TM";
            case "BANK_TRANSFER","TRANSFER", "CARD", "MOMO","VNPAY","ZALOPAY"  -> "CK";
            default                          -> "TM/CK";
        };
    }

    private String mapPaymentMethodCode(String m) {
        if (m == null) return "1";
        return switch (m.toUpperCase()) {
            case "CASH"                                -> "1";
            case "BANK_TRANSFER","TRANSFER",
                 "MOMO","VNPAY","ZALOPAY","CARD"      -> "2";
            default                                    -> "1";  // fallback tiền mặt
        };
    }
}