package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
@Log4j2
@PreAuthorize("hasAnyRole('SELLER', 'ADMIN', 'POS')")
public class FileUploadController {

    private final FileStorageService fileStorageService;

    // Helper: build response map
    private Map<String, String> buildResult(String imageUrl) {
        return Map.of(
                "imageUrl", imageUrl,
                "filename", imageUrl.substring(imageUrl.lastIndexOf('/') + 1)
        );
    }

    @PostMapping(value = "/categories/upload-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<String>> uploadCategoryImage(
            @RequestParam("file") MultipartFile file) {
        try {
            String imageUrl = fileStorageService.saveCategoryImage(file);
            log.info("✅ Category image uploaded: {}", imageUrl);
            return ResponseEntity.ok(ApiResponse.success(imageUrl, "Image uploaded successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (IOException e) {
            log.error("❌ Failed to upload category image", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, "Failed to upload image"));
        }
    }

    // PRODUCT
    @PostMapping("/product-image")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadProductImage(
            @RequestParam("image") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Image file is required"));
            }
            String savedPath = fileStorageService.saveProductImage(file);
            return ResponseEntity.ok(ApiResponse.success(buildResult(savedPath), "Image uploaded successfully"));
        } catch (IOException e) {
            log.error("❌ uploadProductImage", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // POS PRODUCT
    @PostMapping("/pos-product-image")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadPosProductImage(
            @RequestParam("image") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Image file is required"));
            }
            String savedPath = fileStorageService.savePosProductImage(file);
            return ResponseEntity.ok(ApiResponse.success(buildResult(savedPath), "POS product image uploaded successfully"));
        } catch (IOException e) {
            log.error("❌ uploadPosProductImage", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }


    // VARIANT
    @PostMapping("/variant-image")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadVariantImage(
            @RequestParam("image") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Image file is required"));
            }
            String savedPath = fileStorageService.saveVariantImage(file);
            return ResponseEntity.ok(ApiResponse.success(buildResult(savedPath), "Variant image uploaded successfully"));
        } catch (IOException e) {
            log.error("❌ uploadVariantImage", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // MULTIPLE PRODUCT IMAGES
    @PostMapping("/product-images")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadProductImages(
            @RequestParam("images") List<MultipartFile> files) {
        try {
            if (files == null || files.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "At least one image is required"));
            }
            if (files.size() > 5) {
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Maximum 5 images allowed"));
            }

            List<Map<String, String>> uploaded = new ArrayList<>();
            for (MultipartFile f : files) {
                if (!f.isEmpty()) {
                    String savedPath = fileStorageService.saveProductImage(f);
                    uploaded.add(buildResult(savedPath));
                }
            }

            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("images", uploaded, "count", uploaded.size()),
                    "Images uploaded successfully"));
        } catch (IOException e) {
            log.error("❌ uploadProductImages", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }
}