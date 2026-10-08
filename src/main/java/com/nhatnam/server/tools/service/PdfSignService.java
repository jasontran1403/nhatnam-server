package com.nhatnam.server.tools.service;

// ── iText 5 (com.itextpdf:itextpdf) ──────────────────────────────────────────
// CỐ Ý dùng iText 5 chứ không phải iText 7/9. Lý do:
//
//   • Project đang kéo com.itextpdf:html2pdf:6.3.1 → iText Core 9.x.
//     Từ iText 9, lớp PdfSignatureAppearance và PdfSigner.getSignatureAppearance()
//     đã BỊ XÓA (deprecated ở 8.0, remove ở 9.0). Code ký gốc bên project util
//     viết theo API iText 7 nên bê nguyên sang đây sẽ KHÔNG BIÊN DỊCH ĐƯỢC.
//
//   • Không thể thêm com.itextpdf:sign:7.x vì kernel/layout 7.x sẽ xung đột
//     version với kernel 9.x mà html2pdf đang dùng (cùng groupId/artifactId).
//
//   • com.itextpdf:itextpdf:5.5.13.5 ĐÃ CÓ SẴN trong pom (khác package hoàn toàn:
//     com.itextpdf.text.* so với com.itextpdf.kernel.*) nên chạy song song với
//     iText 9 không vấn đề gì, và API ký của nó cho phép tự vẽ appearance
//     2 cột + logo mờ đúng như thiết kế ban đầu.
//
// Nếu sau này muốn chuyển hẳn sang API ký của iText 9 (SignerProperties +
// SignatureFieldAppearance) thì thay riêng file này, phần còn lại không đổi.
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.Image;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.ColumnText;
import com.itextpdf.text.pdf.PdfGState;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfSignatureAppearance;
import com.itextpdf.text.pdf.PdfStamper;
import com.itextpdf.text.pdf.PdfTemplate;
import com.itextpdf.text.pdf.security.BouncyCastleDigest;
import com.itextpdf.text.pdf.security.DigestAlgorithms;
import com.itextpdf.text.pdf.security.ExternalDigest;
import com.itextpdf.text.pdf.security.ExternalSignature;
import com.itextpdf.text.pdf.security.MakeSignature;
import com.itextpdf.text.pdf.security.PrivateKeySignature;

import com.nhatnam.server.tools.config.Pkcs11Properties;
import com.nhatnam.server.tools.dto.ToolsDto.SignZone;
import com.nhatnam.server.tools.service.ToolsTokenService.TokenCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * Ký PDF tại đúng vị trí người dùng chọn trên frontend.
 *
 * Tọa độ nhận vào là % kích thước trang, gốc ở góc TRÊN-TRÁI (hệ DOM).
 * PDF dùng gốc ở góc DƯỚI-TRÁI nên phải lật trục Y — xem toPdfRect().
 *
 * Mỗi vùng ký là một chữ ký độc lập, ký tuần tự ở chế độ append để chữ ký
 * trước không bị vô hiệu khi thêm chữ ký sau.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class PdfSignService {

    private static final DateTimeFormatter SIGN_TIME =
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss");

    /** Số dòng của khối chữ bên phải — dùng để quy đổi "dịch lên N dòng" ra pixel */
    private static final float TEXT_LINE_COUNT = 6f;

    private final ToolsTokenService tokenService;
    private final Pkcs11Properties  props;

    /**
     * @param pdfBytes nội dung PDF gốc
     * @param zones    danh sách vùng ký
     * @param pin      PIN USB token — bị xóa khỏi bộ nhớ ngay sau khi nạp khóa
     */
    public byte[] sign(byte[] pdfBytes, List<SignZone> zones, char[] pin) throws Exception {
        TokenCredentials creds = tokenService.loadCredentials(pin);
        Arrays.fill(pin, '\0');

        Certificate[] chain = new Certificate[]{ creds.certificate() };
        String commonName = extractCommonName(creds.certificate());

        byte[] result = pdfBytes;
        for (int i = 0; i < zones.size(); i++) {
            result = signOneZone(result, zones.get(i), i, creds, chain, commonName);
            log.info("[Tools][Sign] Đã ký vùng {}/{}", i + 1, zones.size());
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────

    private byte[] signOneZone(byte[] pdfBytes, SignZone zone, int zoneIndex,
                               TokenCredentials creds, Certificate[] chain,
                               String certCommonName) throws Exception {

        PdfReader reader = new PdfReader(pdfBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // append = true → giữ nguyên các chữ ký đã có trước đó
        PdfStamper stamper = PdfStamper.createSignature(reader, out, '\0', null, true);

        int pageNum = zone.getPage() + 1;
        Rectangle rect = toPdfRect(zone, reader.getPageSize(pageNum));

        PdfSignatureAppearance appearance = stamper.getSignatureAppearance();
        appearance.setVisibleSignature(rect, pageNum,
                "sig_" + zoneIndex + "_" + System.currentTimeMillis());
        appearance.setRenderingMode(PdfSignatureAppearance.RenderingMode.DESCRIPTION);
        appearance.setLayer2Text("");   // để trống — tự vẽ toàn bộ bên dưới

        String displayName = certCommonName != null ? certCommonName : zone.getSignerName();
        drawAppearance(appearance, rect, displayName);

        ExternalSignature pks = new PrivateKeySignature(
                creds.privateKey(), DigestAlgorithms.SHA256, tokenService.getProviderName());
        ExternalDigest digest = new BouncyCastleDigest();

        MakeSignature.signDetached(appearance, digest, pks, chain,
                null, null, null, 0, MakeSignature.CryptoStandard.CMS);

        return out.toByteArray();
    }

    /**
     * Đổi % (gốc trên-trái, hệ DOM) sang Rectangle của PDF (gốc dưới-trái).
     * Đây là chỗ dễ sai nhất khi chữ ký bị lệch vị trí.
     * Lưu ý iText 5 nhận (llx, lly, urx, ury) — hai GÓC, không phải rộng/cao.
     */
    private Rectangle toPdfRect(SignZone zone, Rectangle pageSize) {
        float pageW = pageSize.getWidth();
        float pageH = pageSize.getHeight();

        float llx = (float) (zone.getX() / 100.0) * pageW;
        float urx = (float) ((zone.getX() + zone.getW()) / 100.0) * pageW;
        float ury = pageH - (float) (zone.getY() / 100.0) * pageH;
        float lly = pageH - (float) ((zone.getY() + zone.getH()) / 100.0) * pageH;

        return new Rectangle(llx, lly, urx, ury);
    }

    /**
     * Vẽ nội dung chữ ký vào layer 2.
     * Hệ tọa độ của layer là cục bộ: gốc ở góc dưới-trái của chính ô chữ ký.
     */
    private void drawAppearance(PdfSignatureAppearance appearance, Rectangle rect,
                                String commonName) throws Exception {

        float sigW = rect.getWidth();
        float sigH = rect.getHeight();
        PdfTemplate layer = appearance.getLayer(2);

        drawStampImage(layer, sigW, sigH);

        BaseFont baseFont = loadVietnameseFont();
        float padding = 3f;
        float colLeft  = sigW * 0.45f;
        float colRight = sigW * 0.52f;

        float sizeLeft  = Math.max(4f, Math.min(sigH * 0.28f, colLeft  * 0.15f));
        float sizeRight = Math.max(3.5f, Math.min(sigH * 0.17f, colRight * 0.11f));

        BaseColor ink = new BaseColor(30, 30, 30);

        // Cột trái — tên người ký
        ColumnText left = new ColumnText(layer);
        left.setSimpleColumn(padding, padding, colLeft - padding, sigH - padding);
        left.setAlignment(Element.ALIGN_LEFT);
        left.addElement(new Paragraph(commonName,
                new Font(baseFont, sizeLeft, Font.BOLD, ink)));
        left.go();

        // Cột phải — thông tin ký
        String rightText = "Digitally signed by\n" + commonName
                + "\nDate: " + LocalDateTime.now().format(SIGN_TIME) + " +07'00'";

        ColumnText right = new ColumnText(layer);
        right.setSimpleColumn(colLeft + padding, padding, sigW - padding, sigH - padding);
        right.setAlignment(Element.ALIGN_LEFT);
        right.addElement(new Paragraph(rightText,
                new Font(baseFont, sizeRight, Font.NORMAL, ink)));
        right.go();
    }

    /**
     * Vẽ ảnh dấu/chữ ký đè lên ô chữ ký — thứ tạo ra vẻ ngoài giống Foxit/Adobe.
     *
     * Vẽ TRƯỚC phần chữ nên chữ nằm trên ảnh, ảnh làm nền mờ phía sau.
     * Ảnh canh giữa và co theo CHIỀU CAO ô ký (giữ đúng tỉ lệ gốc), nên nó trải
     * từ đầu tới cuối khối chữ. Chỉ thu nhỏ lại khi bề rộng vượt quá ô ký.
     *
     * Thứ tự tìm ảnh:
     *   1. tools.pkcs11.sign-logo-path  (file trên đĩa máy chủ)
     *   2. classpath /sign/stamp.png    ← đặt con dấu của công ty ở đây
     *   3. classpath /watermarks/logo.png (dùng tạm logo có sẵn)
     * Đặt sign-logo-path = "none" để tắt hẳn.
     */
    private void drawStampImage(PdfTemplate layer, float sigW, float sigH) {
        String configured = props.getSignLogoPath();
        if ("none".equalsIgnoreCase(configured)) return;

        try {
            Image stamp = loadStampImage(configured);
            if (stamp == null) return;

            // Co theo CHIỀU CAO ô ký để ảnh trải từ đầu tới cuối khối chữ.
            // Trước đây co theo bề rộng nên ảnh luôn nhỏ hơn khối chữ ở những
            // ô ký nằm ngang (rộng > cao) — đúng trường hợp đang dùng.
            float targetH = sigH * (float) clampPositive(props.getSignLogoScale(), 1.0);
            float targetW = targetH * stamp.getWidth() / stamp.getHeight();

            // Không để tràn ngang ra ngoài ô ký
            if (targetW > sigW) {
                targetW = sigW;
                targetH = targetW * stamp.getHeight() / stamp.getWidth();
            }

            float x = (sigW - targetW) / 2f;

            // Dịch lên trên theo số DÒNG chữ. Khối chữ bên phải có 6 dòng
            // (Digitally signed by / tên công ty 3 dòng / Date / giờ) nên lấy
            // chiều cao một dòng = sigH / 6.
            float lineHeight = sigH / TEXT_LINE_COUNT;
            float y = (sigH - targetH) / 2f + lineHeight * (float) props.getSignLogoOffsetLines();

            // Giữ ảnh nằm trọn trong ô ký — vượt ra ngoài sẽ bị cắt cụt.
            // Lưu ý: sign-logo-scale = 1.0 thì ảnh đã cao bằng ô ký, không còn
            // chỗ để dịch, phải hạ scale xuống mới thấy tác dụng.
            y = Math.max(0, Math.min(y, sigH - targetH));

            PdfGState gs = new PdfGState();
            gs.setFillOpacity((float) clamp01(props.getSignLogoOpacity(), 0.36));

            layer.saveState();
            layer.setGState(gs);
            layer.addImage(stamp, targetW, 0, 0, targetH, x, y);
            layer.restoreState();

        } catch (Exception e) {
            // Thiếu ảnh dấu không đáng để hỏng cả chữ ký — vẫn ký, chỉ là không có dấu
            log.warn("[Tools][Sign] Không vẽ được ảnh dấu: {}", e.getMessage());
        }
    }

    /** @return null nếu không tìm thấy ảnh nào */
    private Image loadStampImage(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            try {
                return Image.getInstance(configuredPath);
            } catch (Exception e) {
                log.warn("[Tools][Sign] Không đọc được ảnh dấu '{}': {}", configuredPath, e.getMessage());
            }
        }
        for (String resource : new String[]{"/sign/stamp.png", "/watermarks/logo.png"}) {
            try (InputStream is = getClass().getResourceAsStream(resource)) {
                if (is == null) continue;
                return Image.getInstance(is.readAllBytes());
            } catch (Exception ignored) {
                // thử ảnh tiếp theo
            }
        }
        return null;
    }

    private static double clamp01(double v, double fallback) {
        if (Double.isNaN(v) || v <= 0 || v > 1) return fallback;
        return v;
    }

    /** Cho phép > 1 để ảnh dấu tràn cao hơn khối chữ nếu muốn */
    private static double clampPositive(double v, double fallback) {
        if (Double.isNaN(v) || v <= 0 || v > 3) return fallback;
        return v;
    }

    /** Font có dấu tiếng Việt; không load được thì fallback Helvetica (mất dấu). */
    private BaseFont loadVietnameseFont() throws Exception {
        String path = props.getFontRegularPath();
        if (path != null && !path.isBlank()) {
            try {
                return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            } catch (Exception e) {
                log.warn("[Tools][Sign] Không load được font '{}', tên ký sẽ mất dấu: {}",
                        path, e.getMessage());
            }
        }
        return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
    }

    private String extractCommonName(X509Certificate cert) {
        String subjectDN = cert.getSubjectX500Principal().getName(X500Principal.RFC2253);
        for (String part : subjectDN.split(",")) {
            String p = part.trim();
            if (p.startsWith("CN=")) return p.substring(3);
        }
        return null;
    }
}