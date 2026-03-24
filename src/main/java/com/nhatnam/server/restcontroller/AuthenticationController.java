package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.request.AuthLoginRequest;
import com.nhatnam.server.dto.request.AuthRegisterRequest;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.dto.response.AppVersionCheckResponse;
import com.nhatnam.server.dto.response.AuthResponse;
import com.nhatnam.server.entity.AppVersion;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.AppVersionRepository;
import com.nhatnam.server.service.AuthService;
import com.nhatnam.server.service.FileStorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Log4j2
public class AuthenticationController {
    private final AuthService authService;
    private final FileStorageService fileStorageService;
    private final AppVersionRepository appVersionRepository;
//
//    @Value("${stripe.secret-key}")
//    private String stripeSecretKey;

    @GetMapping("/fetch-version")
    public ResponseEntity<ApiResponse<Map<String, Object>>> fetchVersion(
            @RequestParam String platform,
            @RequestParam(required = false, defaultValue = "0.0.0") String version,
            @RequestParam(required = false, defaultValue = "0") int build
    ) {
        try {
            Optional<AppVersion> opt = appVersionRepository
                    .findByPlatform(platform.toLowerCase().trim());

            if (opt.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success(
                        StatusCode.SUCCESS,
                        Map.of(
                                "latestVersion", version,
                                "latestBuild",   build,
                                "message",       ""
                        ),
                        "No config"));
            }

            AppVersion config = opt.get();

            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("latestVersion", config.getLatestVersion());
            data.put("latestBuild",   config.getLatestBuild());
            data.put("message",       config.getMessage() != null
                    ? config.getMessage() : "");

            return ResponseEntity.ok(ApiResponse.success(
                    StatusCode.SUCCESS, data, "OK"));

        } catch (Exception e) {
            log.error("[fetch-version] Error: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(
                    StatusCode.SUCCESS,
                    Map.of("latestVersion", "", "latestBuild", 0, "message", ""),
                    "Error"));
        }
    }

    @GetMapping("/version-check")
    public ResponseEntity<ApiResponse<AppVersionCheckResponse>> checkVersion(
            @RequestParam String platform,
            @RequestParam String version,
            @RequestParam(defaultValue = "0") int build) {  // ← thêm build param

        log.info("[VersionCheck] platform={} version={} build={}", platform, version, build);

        try {
            Optional<AppVersion> opt = appVersionRepository.findByPlatform(platform.toLowerCase().trim());

            if (opt.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success(StatusCode.SUCCESS,
                        noUpdate(), "No config"));
            }

            AppVersion config = opt.get();

            int cmpVersion    = compareVersions(version, config.getLatestVersion());
            int cmpMinVersion = compareVersions(version, config.getMinVersion());

            // So sánh build chỉ khi version BẰNG NHAU
            // Nếu version khác nhau thì dùng version để quyết định
            boolean sameAsLatest = cmpVersion == 0;
            boolean sameAsMin    = cmpMinVersion == 0;

            // hasUpdate: version < latest, HOẶC cùng version nhưng build < latestBuild
            boolean hasUpdate =
                    cmpVersion < 0 ||
                            (sameAsLatest && config.getLatestBuild() > 0 && build < config.getLatestBuild());

            // updateRequired: version < min, HOẶC cùng version nhưng build < minBuild
            boolean updateRequired =
                    cmpMinVersion < 0 ||
                            (sameAsMin && config.getMinBuild() > 0 && build < config.getMinBuild());

            if (!hasUpdate) {
                return ResponseEntity.ok(ApiResponse.success(StatusCode.SUCCESS,
                        noUpdate(), "Up to date"));
            }

            return ResponseEntity.ok(ApiResponse.success(
                    StatusCode.SUCCESS,
                    AppVersionCheckResponse.builder()
                            .hasUpdate(true)
                            .updateRequired(updateRequired)
                            .latestVersion(config.getLatestVersion())
                            .latestBuild(config.getLatestBuild())
                            .downloadUrl(config.getDownloadUrl() != null ? config.getDownloadUrl() : "")
                            .message(config.getMessage() != null
                                    ? config.getMessage()
                                    : "Có phiên bản mới, vui lòng cập nhật.")
                            .build(),
                    updateRequired ? "Force update" : "Soft update"
            ));

        } catch (Exception e) {
            log.error("[VersionCheck] Error: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(StatusCode.SUCCESS,
                    noUpdate(), "Check failed, skip"));
        }
    }

//    @PostMapping("/create-payment-intent")
//    public ResponseEntity<ApiResponse<Object>> createPaymentIntent(
//            @RequestBody Map<String, Object> requestBody) {
//
//        try {
//            Stripe.apiKey = stripeSecretKey;
//
//            String title = (String) requestBody.get("title");
//            Number amountRaw = (Number) requestBody.get("amount");
//            Long productId = ((Number) requestBody.get("productId")).longValue();
//
//            if (title == null || amountRaw == null || productId == null) {
//                return ResponseEntity.badRequest().body(
//                        ApiResponse.error(StatusCode.BAD_REQUEST, "Thiếu thông tin")
//                );
//            }
//
//            double amount = amountRaw.doubleValue();
//            long unitAmount = Math.round(amount * 100); // cent
//
//            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
//                    .setAmount(unitAmount)
//                    .setCurrency("usd")
//                    .setDescription(title)
//                    .putMetadata("productId", String.valueOf(productId))
//                    .build();
//
//            PaymentIntent paymentIntent = PaymentIntent.create(params);
//
//            Map<String, String> responseData = new HashMap<>();
//            responseData.put("clientSecret", paymentIntent.getClientSecret());
//            responseData.put("paymentIntentId", paymentIntent.getId()); // Trả về ID để có thể hủy sau
//
//            return ResponseEntity.ok(
//                    ApiResponse.success(StatusCode.SUCCESS, responseData, "Tạo PaymentIntent thành công")
//            );
//
//        } catch (StripeException e) {
//            log.error("Lỗi Stripe: {}", e.getMessage());
//            return ResponseEntity.ok(
//                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
//            );
//        } catch (Exception e) {
//            log.error("Lỗi hệ thống: {}", e.getMessage());
//            return ResponseEntity.ok(
//                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
//            );
//        }
//    }
//
//    @PostMapping("/cancel-payment-intent")
//    public ResponseEntity<ApiResponse<Object>> cancelPaymentIntent(
//            @RequestBody Map<String, String> requestBody) {
//
//        try {
//            Stripe.apiKey = stripeSecretKey;
//
//            String paymentIntentId = requestBody.get("paymentIntentId");
//
//            if (paymentIntentId == null || paymentIntentId.isEmpty()) {
//                return ResponseEntity.badRequest().body(
//                        ApiResponse.error(StatusCode.BAD_REQUEST, "Thiếu paymentIntentId")
//                );
//            }
//
//            // Lấy PaymentIntent
//            PaymentIntent paymentIntent = PaymentIntent.retrieve(paymentIntentId);
//
//            // Kiểm tra trạng thái hiện tại
//            String currentStatus = paymentIntent.getStatus();
//
//            // Chỉ hủy nếu chưa thành công hoặc chưa bị hủy
//            if ("succeeded".equals(currentStatus)) {
//                return ResponseEntity.ok(
//                        ApiResponse.error(StatusCode.BAD_REQUEST, "Không thể hủy thanh toán đã thành công")
//                );
//            }
//
//            if ("canceled".equals(currentStatus)) {
//                return ResponseEntity.ok(
//                        ApiResponse.error(StatusCode.BAD_REQUEST, "Thanh toán đã bị hủy trước đó")
//                );
//            }
//
//            // Hủy PaymentIntent
//            PaymentIntentCancelParams cancelParams = PaymentIntentCancelParams.builder()
//                    .build();
//
//            PaymentIntent canceledPaymentIntent = paymentIntent.cancel(cancelParams);
//
//            Map<String, Object> responseData = new HashMap<>();
//            responseData.put("paymentIntentId", canceledPaymentIntent.getId());
//            responseData.put("status", canceledPaymentIntent.getStatus());
//            responseData.put("cancellationReason", "customer_canceled");
//
//            return ResponseEntity.ok(
//                    ApiResponse.success(StatusCode.SUCCESS, responseData, "Hủy thanh toán thành công")
//            );
//
//        } catch (StripeException e) {
//            log.error("Lỗi Stripe khi hủy PaymentIntent: {}", e.getMessage());
//            return ResponseEntity.ok(
//                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, "Lỗi Stripe: " + e.getMessage())
//            );
//        } catch (Exception e) {
//            log.error("Lỗi hệ thống khi hủy PaymentIntent: {}", e.getMessage());
//            return ResponseEntity.ok(
//                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, "Lỗi hệ thống: " + e.getMessage())
//            );
//        }
//    }

    /**
     * Đăng ký tài khoản mới
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Object>> register(@Valid @RequestBody AuthRegisterRequest request) {
        try {
            authService.register(request);
            return ResponseEntity.ok(
                    ApiResponse.success(StatusCode.SUCCESS, Collections.emptyMap(), "Register successfully")
            );
        } catch (Exception e) {
            log.error("Register failed: {}", e.getMessage());
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage())
            );
        }
    }

    /**
     * Đăng nhập
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@RequestBody AuthLoginRequest request) {
        try {
            AuthResponse response = authService.login(request);
            return ResponseEntity.ok(
                    ApiResponse.success(StatusCode.SUCCESS, response, "Login successfully")
            );
        } catch (Exception e) {
            log.error("Login failed from username {}, message: {}", request.getUsername(), e.getMessage());
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.WRONG_PASSWORD, e.getMessage())
            );
        }
    }

    /**
     * Serve ảnh (không cần JWT authentication)
     * GET /api/auth/images/{type}/{filename}
     * type: product, variant, ingredient
     * filename: tên file ảnh
     */
    @GetMapping("/images/{type}/{filename}")
    public ResponseEntity<byte[]> serveImage(
            @PathVariable String type,
            @PathVariable String filename) {

        try {
            // Validate type - ADD INGREDIENT HERE
            if (!type.equals("product")
                    && !type.equals("pos-product")
                    && !type.equals("category")
                    && !type.equals("variant")
                    && !type.equals("ingredient")) {
                log.warn("⚠️ Invalid image type requested: {}", type);
                return ResponseEntity.badRequest().build();
            }

            // Construct file path
            String filePath = "/images/" + type + "/" + filename;

            // Get file content
            byte[] imageBytes = fileStorageService.getFile(filePath);

            // Determine content type (all images are PNG)
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setCacheControl("max-age=86400");

            return new ResponseEntity<>(imageBytes, headers, HttpStatus.OK);
        } catch (IOException e) {
            log.error("❌ Failed to serve image: {}/{}", type, filename, e);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("❌ Unexpected error serving image: {}/{}", type, filename, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private AppVersionCheckResponse noUpdate() {
        return AppVersionCheckResponse.builder()
                .hasUpdate(false)
                .updateRequired(false)
                .build();
    }

    private int compareVersions(String a, String b) {
        String[] pa = a.trim().split("\\.");
        String[] pb = b.trim().split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int nb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (na != nb) return Integer.compare(na, nb);
        }
        return 0;
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (NumberFormatException e) { return 0; }
    }
}