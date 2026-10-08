package com.nhatnam.server.restcontroller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nhatnam.server.dto.request.AuthLoginRequest;
import com.nhatnam.server.dto.request.AuthRegisterRequest;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.dto.response.AppVersionCheckResponse;
import com.nhatnam.server.dto.response.AuthResponse;
import com.nhatnam.server.entity.AppVersion;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.AppVersionRepository;
import com.nhatnam.server.service.AuthService;
import com.nhatnam.server.service.POSFileStorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Log4j2
public class AuthenticationController {

    private final AuthService authService;
    private final POSFileStorageService fileStorageService;
    private final AppVersionRepository appVersionRepository;

    // ================================================================
    // ME
    // ================================================================

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me() {

        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        log.info(
                "Authentication: {}",
                authentication
        );

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {

            return ResponseEntity.ok(
                    ApiResponse.error(
                            StatusCode.BAD_REQUEST,
                            "Not authenticated"
                    )
            );
        }

        Map<String, Object> data =
                new java.util.LinkedHashMap<>();

        data.put(
                "username",
                authentication.getName()
        );

        data.put(
                "authorities",
                authentication
                        .getAuthorities()
                        .stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList()
        );

        return ResponseEntity.ok(
                ApiResponse.success(
                        StatusCode.SUCCESS,
                        data,
                        "OK"
                )
        );
    }

    // ================================================================
    // FETCH VERSION
    // ================================================================

    @GetMapping("/fetch-version")
    public ResponseEntity<ApiResponse<Map<String, Object>>> fetchVersion(
            @RequestParam String platform,
            @RequestParam(
                    required = false,
                    defaultValue = "0.0.0"
            )
            String version,
            @RequestParam(
                    required = false,
                    defaultValue = "0"
            )
            int build
    ) {

        try {

            Optional<AppVersion> opt =
                    appVersionRepository
                            .findByPlatform(
                                    platform
                                            .toLowerCase()
                                            .trim()
                            );

            if (opt.isEmpty()) {

                return ResponseEntity.ok(
                        ApiResponse.success(
                                StatusCode.SUCCESS,
                                Map.of(
                                        "latestVersion",
                                        version,
                                        "latestBuild",
                                        build,
                                        "message",
                                        ""
                                ),
                                "No config"
                        )
                );
            }

            AppVersion config =
                    opt.get();

            Map<String, Object> data =
                    new java.util.LinkedHashMap<>();

            data.put(
                    "latestVersion",
                    config.getLatestVersion()
            );

            data.put(
                    "latestBuild",
                    config.getLatestBuild()
            );

            data.put(
                    "message",
                    config.getMessage() != null
                            ? config.getMessage()
                            : ""
            );

            return ResponseEntity.ok(
                    ApiResponse.success(
                            StatusCode.SUCCESS,
                            data,
                            "OK"
                    )
            );

        } catch (Exception e) {

            log.error(
                    "[fetch-version] Error: {}",
                    e.getMessage()
            );

            return ResponseEntity.ok(
                    ApiResponse.success(
                            StatusCode.SUCCESS,
                            Map.of(
                                    "latestVersion",
                                    "",
                                    "latestBuild",
                                    0,
                                    "message",
                                    ""
                            ),
                            "Error"
                    )
            );
        }
    }

    // ================================================================
    // VERSION CHECK
    // ================================================================

    @GetMapping("/version-check")
    public ResponseEntity<ApiResponse<AppVersionCheckResponse>> checkVersion(
            @RequestParam String platform,
            @RequestParam String version,
            @RequestParam(defaultValue = "0")
            int build
    ) {

        try {

            Optional<AppVersion> opt =
                    appVersionRepository
                            .findByPlatform(
                                    platform
                                            .toLowerCase()
                                            .trim()
                            );

            if (opt.isEmpty()) {

                return ResponseEntity.ok(
                        ApiResponse.success(
                                StatusCode.SUCCESS,
                                noUpdate(),
                                "No config"
                        )
                );
            }

            AppVersion config =
                    opt.get();

            int cmpVersion =
                    compareVersions(
                            version,
                            config.getLatestVersion()
                    );

            int cmpMinVersion =
                    compareVersions(
                            version,
                            config.getMinVersion()
                    );

            boolean sameAsLatest =
                    cmpVersion == 0;

            boolean sameAsMin =
                    cmpMinVersion == 0;

            boolean hasUpdate =
                    cmpVersion < 0 ||
                            (
                                    sameAsLatest &&
                                            config.getLatestBuild() > 0 &&
                                            build < config.getLatestBuild()
                            );

            boolean updateRequired =
                    cmpMinVersion < 0 ||
                            (
                                    sameAsMin &&
                                            config.getMinBuild() > 0 &&
                                            build < config.getMinBuild()
                            );

            if (!hasUpdate) {

                return ResponseEntity.ok(
                        ApiResponse.success(
                                StatusCode.SUCCESS,
                                noUpdate(),
                                "Up to date"
                        )
                );
            }

            return ResponseEntity.ok(
                    ApiResponse.success(
                            StatusCode.SUCCESS,
                            AppVersionCheckResponse
                                    .builder()
                                    .hasUpdate(true)
                                    .updateRequired(updateRequired)
                                    .latestVersion(
                                            config.getLatestVersion()
                                    )
                                    .latestBuild(
                                            config.getLatestBuild()
                                    )
                                    .downloadUrl(
                                            config.getDownloadUrl() != null
                                                    ? config.getDownloadUrl()
                                                    : ""
                                    )
                                    .message(
                                            config.getMessage() != null
                                                    ? config.getMessage()
                                                    : "Có phiên bản mới, vui lòng cập nhật."
                                    )
                                    .build(),
                            updateRequired
                                    ? "Force update"
                                    : "Soft update"
                    )
            );

        } catch (Exception e) {

            log.error(
                    "[VersionCheck] Error: {}",
                    e.getMessage()
            );

            return ResponseEntity.ok(
                    ApiResponse.success(
                            StatusCode.SUCCESS,
                            noUpdate(),
                            "Check failed, skip"
                    )
            );
        }
    }

    // ================================================================
    // REGISTER
    // ================================================================

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Object>> register(
            @Valid
            @RequestBody
            AuthRegisterRequest request
    ) {

        try {

            authService.register(request);

            return ResponseEntity.ok(
                    ApiResponse.success(
                            StatusCode.SUCCESS,
                            Collections.emptyMap(),
                            "Register successfully"
                    )
            );

        } catch (Exception e) {

            log.error(
                    "Register failed: {}",
                    e.getMessage()
            );

            return ResponseEntity.ok(
                    ApiResponse.error(
                            StatusCode.BAD_REQUEST,
                            e.getMessage()
                    )
            );
        }
    }

    // ================================================================
    // LOGIN
    // ================================================================

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @RequestBody AuthLoginRequest request
    ) {

        try {

            AuthResponse response =
                    authService.login(request);

            return ResponseEntity.ok(
                    ApiResponse.success(
                            StatusCode.SUCCESS,
                            response,
                            "Login successfully"
                    )
            );

        } catch (Exception e) {

            log.error(
                    "Login failed from username {}, message: {}",
                    request.getUsername(),
                    e.getMessage()
            );

            return ResponseEntity.ok(
                    ApiResponse.error(
                            StatusCode.WRONG_PASSWORD,
                            e.getMessage()
                    )
            );
        }
    }

    // ================================================================
    // IMAGES
    // ================================================================

    @GetMapping("/images/{type}/{filename}")
    public ResponseEntity<byte[]> serveImage(
            @PathVariable String type,
            @PathVariable String filename
    ) {

        try {

            if (!type.equals("product")
                    && !type.equals("pos-product")
                    && !type.equals("category")
                    && !type.equals("variant")
                    && !type.equals("ingredient")
                    && !type.equals("seller-import")) {

                log.warn(
                        "Invalid image type requested: {}",
                        type
                );

                return ResponseEntity
                        .badRequest()
                        .build();
            }

            String filePath =
                    "/images/" +
                            type +
                            "/" +
                            filename;

            byte[] imageBytes =
                    fileStorageService.getFile(
                            filePath
                    );

            if (imageBytes == null) {

                return ResponseEntity
                        .notFound()
                        .build();
            }

            HttpHeaders headers =
                    new HttpHeaders();

            headers.setContentType(
                    MediaType.IMAGE_PNG
            );

            headers.setCacheControl(
                    "max-age=86400"
            );

            return new ResponseEntity<>(
                    imageBytes,
                    headers,
                    HttpStatus.OK
            );

        } catch (Exception e) {

            log.error(
                    "Unexpected error serving image: {}/{}",
                    type,
                    filename,
                    e
            );

            return ResponseEntity
                    .internalServerError()
                    .build();
        }
    }

    // ================================================================
    // VERSION HELPERS
    // ================================================================

    private AppVersionCheckResponse noUpdate() {

        return AppVersionCheckResponse
                .builder()
                .hasUpdate(false)
                .updateRequired(false)
                .build();
    }

    private int compareVersions(
            String a,
            String b
    ) {

        String[] pa =
                a.trim().split("\\.");

        String[] pb =
                b.trim().split("\\.");

        int len =
                Math.max(
                        pa.length,
                        pb.length
                );

        for (int i = 0; i < len; i++) {

            int na =
                    i < pa.length
                            ? parseIntSafe(pa[i])
                            : 0;

            int nb =
                    i < pb.length
                            ? parseIntSafe(pb[i])
                            : 0;

            if (na != nb)
                return Integer.compare(
                        na,
                        nb
                );
        }

        return 0;
    }

    private int parseIntSafe(String s) {

        try {

            return Integer.parseInt(
                    s.replaceAll(
                            "[^0-9]",
                            ""
                    )
            );

        } catch (NumberFormatException e) {

            return 0;
        }
    }
}