package com.nhatnam.server.einvoice.service;

import com.nhatnam.server.entity.Order;
import com.nhatnam.server.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Quản lý invoice_token của đơn sỉ/lẻ + dựng link/QR để khách tự nhập
 * thông tin xuất hóa đơn.
 *
 * Token là UUID ngẫu nhiên (không đoán được từ orderCode) — giống cơ chế
 * đang dùng cho đơn POS.
 *
 * Cấu hình trong application.yml (đều có mặc định, không bắt buộc khai báo):
 *
 * app:
 *   public-base-url: https://www.original-taste.vn
 *   qr-api-url: "https://api.qrserver.com/v1/create-qr-code/?size=300x300&margin=0&data="
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class SaleInvoiceTokenService {

    private final OrderRepository orderRepo;

    @Value("${app.public-base-url:https://www.original-taste.vn}")
    private String publicBaseUrl;

    @Value("${app.qr-api-url:https://api.qrserver.com/v1/create-qr-code/?size=300x300&margin=0&data=}")
    private String qrApiUrl;

    /**
     * Lấy token của đơn, tự sinh và lưu nếu chưa có.
     * Dùng khi in hóa đơn (seller) và khi kế toán mở danh sách.
     */
    @Transactional
    public String ensureToken(Long orderId) {
        Order order = orderRepo.findById(orderId).orElse(null);
        if (order == null) return null;
        return ensureToken(order);
    }

    /** Bản nhận entity — gọi trong transaction sẵn có. */
    public String ensureToken(Order order) {
        if (order.getInvoiceToken() != null && !order.getInvoiceToken().isBlank()) {
            return order.getInvoiceToken();
        }
        String token = UUID.randomUUID().toString();
        order.setInvoiceToken(token);
        order.setUpdatedAt(System.currentTimeMillis());
        orderRepo.save(order);
        log.info("[SaleInvoice] sinh invoice_token cho đơn {}", order.getOrderCode());
        return token;
    }

    /** Link công khai khách quét QR sẽ mở. */
    public String buildPublicUrl(String token) {
        if (token == null || token.isBlank()) return null;
        String base = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        return base + "/invoice/sale/" + token;
    }

    /** URL ảnh QR (PNG) trỏ tới link công khai — nhúng thẳng vào PDF/UI. */
    public String buildQrImageUrl(String token) {
        String url = buildPublicUrl(token);
        if (url == null) return null;
        return qrApiUrl + URLEncoder.encode(url, StandardCharsets.UTF_8);
    }
}
