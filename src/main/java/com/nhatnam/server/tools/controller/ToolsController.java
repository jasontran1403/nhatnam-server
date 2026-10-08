package com.nhatnam.server.tools.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.config.ToolsAuthContext;
import com.nhatnam.server.tools.dto.ToolsDto.SignRequest;
import jakarta.servlet.http.HttpServletRequest;
import com.nhatnam.server.tools.dto.ToolsDto.SignResponse;
import com.nhatnam.server.tools.dto.ToolsDto.SignZone;
import com.nhatnam.server.tools.dto.ToolsDto.WatermarkSettings;
import com.nhatnam.server.tools.service.PdfSignService;
import com.nhatnam.server.tools.service.ToolsTokenService;
import com.nhatnam.server.tools.service.MediaStorageService;
import com.nhatnam.server.tools.service.WatermarkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * Nhóm tiện ích nội bộ: tạo QR, ký số PDF, gắn watermark.
 * Base path: /api/tools
 *
 * KHÔNG yêu cầu đăng nhập: "/api/tools" nằm trong
 * SecurityConfiguration.PUBLIC_API_PREFIXES (để JwtAuthenticationFilter bỏ qua)
 * và trong WHITE_LIST_URL (để authorizeHttpRequests cho qua).
 * Cả hai chỗ đều phải khai, thiếu chỗ nào cũng bị chặn.
 *
 * Các trang tương ứng ở frontend không xuất hiện trên menu — ai biết đường dẫn
 * thì vào. Riêng /sign có nhận PIN USB token, xem cảnh báo bảo mật trong README.
 */
@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
@Log4j2
public class ToolsController {

    private static final int    QR_SIZE      = 1200;
    private static final double LOGO_RATIO   = 0.32;   // logo chiếm ~22% cạnh QR
    private static final int    LOGO_PADDING = 8;     // viền trắng quanh logo (px)
    private static final long   LOGO_MAX_BYTES = 5L * 1024 * 1024; // 5MB
    private static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final WatermarkService     watermarkService;
    private final MediaStorageService  mediaStorage;
    private final PdfSignService    pdfSignService;
    private final ToolsTokenService tokenService;
    private final ObjectMapper      objectMapper;

    // ════════════════════════════════════════════════════════════════
    // 1. QR
    // ════════════════════════════════════════════════════════════════

    /**
     * POST /api/tools/qr/generate
     * multipart: các field dạng text + (tuỳ chọn) logo = file ảnh để chèn giữa QR
     * type = url | text | product
     */
    @PostMapping(value = "/qr/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, String>>> generateQr(
            @RequestParam String type,
            @RequestParam(required = false) String content,
            @RequestParam(required = false) String productName,
            @RequestParam(required = false) String productionDate,
            @RequestParam(required = false) String expiryDate,
            @RequestParam(required = false) String packageWeight,
            @RequestParam(required = false) String batchWeight,
            @RequestPart(value = "logo", required = false) MultipartFile logo) {
        try {
            String qrContent = buildQrContent(type, content, productName,
                    productionDate, expiryDate, packageWeight, batchWeight);

            // CHARACTER_SET=UTF-8 để tiếng Việt có dấu không bị vỡ khi quét
            // ERROR_CORRECTION=H (~30%) để chịu được việc chèn logo vào giữa
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix matrix = new QRCodeWriter()
                    .encode(qrContent, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints);

            BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(matrix);
            BufferedImage composed = overlayLogo(qrImage, logo);

            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(composed, "PNG", png);

            Map<String, String> data = new LinkedHashMap<>();
            data.put("qrImage", "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(png.toByteArray()));
            data.put("type", type);
            data.put("content", qrContent);

            return ResponseEntity.ok(ApiResponse.success(data, "OK"));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Tools][QR] Lỗi tạo QR", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không tạo được mã QR: " + e.getMessage()));
        }
    }

    private String buildQrContent(String type, String content, String productName,
                                  String productionDate, String expiryDate,
                                  String packageWeight, String batchWeight) {
        switch (type == null ? "" : type) {
            case "url", "text" -> {
                if (isBlank(content)) throw new IllegalArgumentException("Nội dung không được để trống");
                return content;
            }
            case "product" -> {
                if (isBlank(productName) || isBlank(productionDate) || isBlank(expiryDate)
                        || isBlank(packageWeight) || isBlank(batchWeight)) {
                    throw new IllegalArgumentException("Thiếu thông tin sản phẩm");
                }
                LocalDate nsx = LocalDate.parse(productionDate);
                LocalDate hsd = LocalDate.parse(expiryDate);
                if (!hsd.isAfter(nsx)) {
                    throw new IllegalArgumentException("Hạn sử dụng phải sau ngày sản xuất");
                }
                return String.format(
                        "Sản phẩm: %s%nNSX: %s%nHSD: %s%nKL gói: %s%nKL mẻ: %s",
                        productName, nsx.format(VN_DATE), hsd.format(VN_DATE),
                        packageWeight, batchWeight);
            }
            default -> throw new IllegalArgumentException("Loại QR không hỗ trợ: " + type);
        }
    }

    /**
     * Vẽ logo do user upload vào giữa QR, có nền trắng bo góc cho dễ quét.
     * Nếu không có logo hoặc logo lỗi -> trả QR gốc, không ném exception.
     */
    private BufferedImage overlayLogo(BufferedImage qr, MultipartFile logoFile) {
        if (logoFile == null || logoFile.isEmpty()) return qr;

        if (logoFile.getSize() > LOGO_MAX_BYTES) {
            throw new IllegalArgumentException("Logo quá lớn (tối đa 5MB).");
        }

        try {
            BufferedImage logo;
            try (ByteArrayInputStream is = new ByteArrayInputStream(logoFile.getBytes())) {
                logo = ImageIO.read(is);
            }
            if (logo == null) {
                throw new IllegalArgumentException("File logo không phải ảnh hợp lệ (PNG/JPG).");
            }

            int qrW      = qr.getWidth();
            int logoSize = (int) (qrW * LOGO_RATIO);

            // Vùng tròn khoét QR, hơi lớn hơn logo một chút
            int clearDiameter = logoSize + LOGO_PADDING * 2;
            int clearX = (qrW - clearDiameter) / 2;
            int clearY = (qrW - clearDiameter) / 2;

            // Crop logo thành hình tròn trước
            BufferedImage circularLogo = cropToCircle(logo, logoSize);

            BufferedImage out = new BufferedImage(qrW, qrW, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // 1) QR gốc
            g.drawImage(qr, 0, 0, null);

            // 2) KHOÉT HÌNH TRÒN trắng
            g.setColor(Color.WHITE);
            g.fillOval(clearX, clearY, clearDiameter, clearDiameter);

            // 3) Logo (đã bo tròn)
            int logoX = (qrW - logoSize) / 2;
            int logoY = (qrW - logoSize) / 2;
            g.drawImage(circularLogo, logoX, logoY, null);

            g.dispose();
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Tools][QR] Không đọc được logo user upload, trả QR không logo: {}", e.getMessage());
            return qr;
        }
    }

    /**
     * Crop ảnh về hình tròn, scale về đúng kích thước yêu cầu.
     * Phần ngoài vòng tròn được để trong suốt.
     */
    private BufferedImage cropToCircle(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Dùng clip hình tròn rồi vẽ ảnh vào — phần ngoài clip bị cắt
        g.setClip(new java.awt.geom.Ellipse2D.Float(0, 0, size, size));

        // Crop vuông ở giữa ảnh gốc trước khi scale (giữ tỉ lệ, không bị méo)
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        int side = Math.min(srcW, srcH);
        int sx = (srcW - side) / 2;
        int sy = (srcH - side) / 2;

        g.drawImage(src, 0, 0, size, size, sx, sy, sx + side, sy + side, null);
        g.dispose();
        return out;
    }

    // ════════════════════════════════════════════════════════════════
    // 2. Watermark
    // ════════════════════════════════════════════════════════════════

    /** Logo dùng làm watermark — frontend tải về để vẽ preview trên canvas */
    @GetMapping("/watermark/logo")
    public ResponseEntity<Resource> getWatermarkLogo() {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(org.springframework.http.CacheControl.maxAge(
                        java.time.Duration.ofHours(1)))
                .body(new ClassPathResource("watermarks/logo.png"));
    }

    /**
     * POST /api/tools/watermark/add
     * multipart: file, settings (JSON WatermarkSettings), type = image | video
     */
    @PostMapping(value = "/watermark/add", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> addWatermark(
            @RequestPart("file") MultipartFile file,
            @RequestPart("settings") String settingsJson,
            @RequestPart("type") String type) {
        try {
            if (file.isEmpty()) {
                return badRequest("Chưa chọn file");
            }
            WatermarkSettings settings = objectMapper.readValue(settingsJson, WatermarkSettings.class);
            boolean isVideo = "video".equalsIgnoreCase(type);

            byte[] result;
            String filename;
            MediaType contentType;

            if (isVideo) {
                result      = watermarkService.addWatermarkToVideo(file, settings);
                filename    = "watermarked_" + System.currentTimeMillis() + ".mp4";
                contentType = MediaType.parseMediaType("video/mp4");
            } else {
                boolean png = isPng(file);
                result      = watermarkService.addWatermarkToImage(file, settings, png ? "png" : "jpg");
                filename    = "watermarked_" + System.currentTimeMillis() + (png ? ".png" : ".jpg");
                contentType = png ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
            }

            return ResponseEntity.ok()
                    .contentType(contentType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(result);

        } catch (ToolsException e) {
            log.warn("[Tools][Watermark] {}", e.getDetail());
            return badRequest(e.getMessage());

        } catch (IllegalArgumentException | IllegalStateException e) {
            // Lỗi môi trường / dữ liệu đầu vào — log gọn, không stack trace
            log.warn("[Tools][Watermark] {}", e.getMessage());
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("[Tools][Watermark] Lỗi không xác định", e);
            return badRequest("Gắn watermark thất bại: " + e.getMessage());
        }
    }

    /**
     * POST /api/tools/watermark/save
     * Giống /watermark/add nhưng KHÔNG trả file về — lưu thẳng vào thư viện
     * tài nguyên rồi trả metadata. Dùng cho luồng trên điện thoại: xử lý xong
     * là thấy ngay ở tab Tài nguyên, tải về lúc nào cũng được.
     */
    @PostMapping(value = "/watermark/save", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> watermarkAndSave(
            HttpServletRequest request,
            @RequestPart("file") MultipartFile file,
            @RequestPart("settings") String settingsJson) {
        try {
            String owner = ToolsAuthContext.username(request);
            if (file.isEmpty()) return ResponseEntity.ok(ApiResponse.error(400, "Chưa chọn file"));

            WatermarkSettings settings = objectMapper.readValue(settingsJson, WatermarkSettings.class);
            String mediaType = MediaStorageService.detectMediaType(
                    file.getContentType(), file.getOriginalFilename());

            byte[] result;
            String contentType;
            String name = stripExtension(file.getOriginalFilename());

            if ("VIDEO".equals(mediaType)) {
                result      = watermarkService.addWatermarkToVideo(file, settings);
                contentType = "video/mp4";
                name       += "_watermark.mp4";
            } else {
                boolean png = isPng(file);
                result      = watermarkService.addWatermarkToImage(file, settings, png ? "png" : "jpg");
                contentType = png ? "image/png" : "image/jpeg";
                name       += png ? "_watermark.png" : "_watermark.jpg";
            }

            var asset = mediaStorage.storeBytes(result, name, contentType, mediaType, owner);
            return ResponseEntity.ok(ApiResponse.success(MediaController.toMap(asset),
                    "Đã lưu vào thư viện"));

        } catch (ToolsException e) {
            log.warn("[Tools][Watermark] {}", e.getDetail());
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[Tools][Watermark] {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Tools][Watermark] Lỗi không xác định", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Gắn watermark thất bại."));
        }
    }

    private String stripExtension(String name) {
        if (name == null || name.isBlank()) return "media";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ════════════════════════════════════════════════════════════════
    // 3. Ký số PDF
    // ════════════════════════════════════════════════════════════════

    /**
     * POST /api/tools/sign
     * multipart: file (PDF), zones (JSON {"zones":[...]})
     * header  : X-Token-Pin — PIN USB token, không bao giờ ghi log
     */
    @PostMapping(value = "/sign", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> sign(
            @RequestPart("file") MultipartFile file,
            @RequestPart("zones") String zonesJson,
            @RequestHeader(value = "X-Token-Pin", required = false) String pinHeader) {

        if (file.isEmpty())  return badRequestSign("File PDF không được để trống.");
        if (!isPdf(file))    return badRequestSign("Chỉ chấp nhận file PDF.");

        String pinStr = !isBlank(pinHeader) ? pinHeader : System.getenv("TOKEN_PIN");
        if (isBlank(pinStr)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new SignResponse(false, "Thiếu PIN token.", null));
        }

        char[] pin = pinStr.toCharArray();
        try {
            SignRequest req = objectMapper.readValue(zonesJson, SignRequest.class);
            List<SignZone> zones = req.getZones();
            if (zones == null || zones.isEmpty()) {
                return badRequestSign("Chưa có vùng ký nào.");
            }

            log.info("[Tools][Sign] Ký file '{}' với {} vùng", file.getOriginalFilename(), zones.size());
            byte[] signed = pdfSignService.sign(file.getBytes(), zones, pin);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + buildOutputName(file.getOriginalFilename()) + "\"")
                    .body(signed);

        } catch (ToolsException e) {
            // Chưa cắm token, sai PIN, sai đường dẫn driver... — không phải bug.
            // Log CHI TIẾT cho quản trị, trả về giao diện bản NGẮN GỌN.
            log.warn("[Tools][Sign] Không ký được: {}", e.getDetail());
            return ResponseEntity.ok(new SignResponse(false, e.getMessage(), null));

        } catch (Exception e) {
            log.error("[Tools][Sign] Ký thất bại (lỗi không xác định): {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new SignResponse(false, "Ký số thất bại: " + e.getMessage(), null));
        } finally {
            Arrays.fill(pin, '\0');
        }
    }

    /** POST /api/tools/sign/token-status — kiểm tra token có cắm và PIN đúng không */
    @PostMapping("/sign/token-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> tokenStatus(
            @RequestHeader(value = "X-Token-Pin", required = false) String pinHeader) {

        String pinStr = !isBlank(pinHeader) ? pinHeader : System.getenv("TOKEN_PIN");
        if (isBlank(pinStr)) {
            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("ready", false, "message", "Chưa nhập PIN"), "OK"));
        }
        char[] pin = pinStr.toCharArray();
        try {
            String problem = tokenService.checkConnection(pin);
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "ready", problem == null,
                    "message", problem == null ? "Token sẵn sàng" : problem
            ), "OK"));
        } finally {
            Arrays.fill(pin, '\0');
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    private ResponseEntity<ApiResponse<Object>> badRequest(String msg) {
        return ResponseEntity.ok(ApiResponse.error(400, msg));
    }

    private ResponseEntity<SignResponse> badRequestSign(String msg) {
        return ResponseEntity.badRequest().body(new SignResponse(false, msg, null));
    }

    private boolean isPdf(MultipartFile f) {
        String ct = f.getContentType();
        String fn = f.getOriginalFilename();
        return "application/pdf".equals(ct)
                || (fn != null && fn.toLowerCase().endsWith(".pdf"));
    }

    private boolean isPng(MultipartFile f) {
        String ct = f.getContentType();
        String fn = f.getOriginalFilename();
        return "image/png".equals(ct)
                || (fn != null && fn.toLowerCase().endsWith(".png"));
    }

    private String buildOutputName(String original) {
        if (original == null) return "signed.pdf";
        return original.replaceAll("(?i)\\.pdf$", "") + "_signed.pdf";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}