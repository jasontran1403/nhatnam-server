package com.nhatnam.server.tools.controller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.config.ToolsAuthContext;
import com.nhatnam.server.tools.entity.FileAsset;
import com.nhatnam.server.tools.repository.FileAssetRepository;
import com.nhatnam.server.tools.service.FileStorageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Kho Tệp — base path /api/tools/files (công khai, xem SecurityConfiguration).
 *
 * File thật phục vụ ở /files/{fileName} qua static handler (MediaWebConfig anh em
 * — xem FileWebConfig) để có HTTP Range; endpoint /download ở đây chỉ dùng khi
 * cần ép tên hiển thị vào Content-Disposition.
 */
@RestController
@RequestMapping("/api/tools/files")
@RequiredArgsConstructor
@Log4j2
public class FileController {

    private final FileStorageService  storage;
    private final FileAssetRepository repo;

    // ════════════════════════════════════════════════════════════════
    // Danh sách
    // ════════════════════════════════════════════════════════════════

    /**
     * GET /api/tools/files
     * page, size, q, ext (lặp lại hoặc phân tách bằng dấu phẩy),
     * from, to (epoch millis), sort = createdAt|name|size, dir = asc|desc
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "40") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> ext,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc")      String dir) {
        try {
            String owner = ToolsAuthContext.username(request);
            // ext=xlsx,pdf và ext=xlsx&ext=pdf đều phải chạy
            List<String> exts = new ArrayList<>();
            if (ext != null) ext.forEach(e -> Collections.addAll(exts, e.split(",")));

            var paged = storage.search(owner, q, from, to, exts, page, size, sort, dir);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content",       paged.getContent().stream().map(FileController::toMap).toList());
            result.put("totalElements", paged.getTotalElements());
            result.put("totalPages",    paged.getTotalPages());
            result.put("currentPage",   paged.getNumber());
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));

        } catch (Exception e) {
            log.error("[Files] list error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không tải được danh sách tệp."));
        }
    }

    /** GET /api/tools/files/facets — đếm theo đuôi (của user hiện tại) */
    @GetMapping("/facets")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> facets(HttpServletRequest request) {
        try {
            String owner = ToolsAuthContext.username(request);
            List<Map<String, Object>> out = repo.countByExt(owner).stream()
                    .map(row -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("ext",   row[0]);
                        m.put("count", row[1]);
                        return m;
                    })
                    .toList();
            return ResponseEntity.ok(ApiResponse.success(out, "OK"));
        } catch (Exception e) {
            log.error("[Files] facets error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không đọc được bộ lọc."));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Đặt tên
    // ════════════════════════════════════════════════════════════════

    /**
     * POST /api/tools/files/check-name — body { name, ext }
     * Cho frontend hiện cảnh báo TRƯỚC khi tải lên, thay vì để người dùng chờ
     * xong 200 MB rồi mới biết tên bị đổi.
     */
    @PostMapping("/check-name")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkName(@RequestBody Map<String, String> body) {
        try {
            var res = storage.resolveName(body.get("name"), body.getOrDefault("ext", ""), null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("finalName", res.finalName());
            m.put("renamed",   res.renamed());
            m.put("requested", res.requestedName());
            return ResponseEntity.ok(ApiResponse.success(m, "OK"));
        } catch (Exception e) {
            log.error("[Files] checkName error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không kiểm tra được tên tệp."));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Tải lên
    // ════════════════════════════════════════════════════════════════

    /** POST /api/tools/files/upload — một file, dùng cho file nhỏ */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            HttpServletRequest request,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "name", required = false) String desiredName) {
        Path temp = null;
        try {
            String owner = ToolsAuthContext.username(request);
            if (file == null || file.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(400, "Chưa chọn tệp để tải lên."));
            }
            // Ghi ra file tạm rồi chuyển, không readAllBytes() — file vài trăm MB
            // nạp hết vào heap là hết bộ nhớ
            temp = Files.createTempFile("upload_", ".tmp");
            file.transferTo(temp.toFile());

            var asset = storage.persist(temp, file.getOriginalFilename(), desiredName, file.getContentType(), owner);
            temp = null;   // persist đã di chuyển file đi

            return ResponseEntity.ok(ApiResponse.success(toMap(asset), "Đã tải lên"));

        } catch (ToolsException e) {
            log.warn("[Files] upload: {}", e.getDetail());
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Files] upload error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tải tệp lên thất bại."));
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
        }
    }

    /** POST /api/tools/files/chunk/init → { uploadId } */
    @PostMapping("/chunk/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chunkInit() {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("uploadId", storage.initChunkUpload()), "OK"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Files] chunkInit error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không khởi tạo được phiên tải lên."));
        }
    }

    /** POST /api/tools/files/chunk/part — multipart: uploadId, index, chunk */
    @PostMapping(value = "/chunk/part", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Object>> chunkPart(
            @RequestPart("uploadId") String uploadId,
            @RequestPart("index")    String index,
            @RequestPart("chunk")    MultipartFile chunk) {
        try {
            storage.saveChunk(uploadId, Integer.parseInt(index), chunk);
            return ResponseEntity.ok(ApiResponse.success(null, "OK"));
        } catch (ToolsException e) {
            log.warn("[Files] chunkPart: {}", e.getDetail());
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Files] chunkPart error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tải phần dữ liệu thất bại."));
        }
    }

    /** POST /api/tools/files/chunk/complete — { uploadId, totalChunks, fileName, name, contentType } */
    @PostMapping("/chunk/complete")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chunkComplete(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        try {
            String owner = ToolsAuthContext.username(request);
            String uploadId = String.valueOf(body.get("uploadId"));
            int totalChunks = Integer.parseInt(String.valueOf(body.get("totalChunks")));
            String fileName = (String) body.get("fileName");
            String desired  = (String) body.get("name");
            String ctype    = (String) body.get("contentType");

            var asset = storage.completeChunkUpload(uploadId, totalChunks, fileName, desired, ctype, owner);
            return ResponseEntity.ok(ApiResponse.success(toMap(asset), "Đã tải lên"));

        } catch (ToolsException e) {
            log.warn("[Files] chunkComplete: {}", e.getDetail());
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Files] chunkComplete error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Hoàn tất tải lên thất bại."));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Sửa
    // ════════════════════════════════════════════════════════════════

    /**
     * Kiểm ownership rồi trả entity. Trả "Không tìm thấy" cho id thuộc user
     * khác để không tiết lộ id có tồn tại.
     */
    private FileAsset findOwned(Long id, String owner) {
        FileAsset a = repo.findById(id)
                .orElseThrow(() -> new ToolsException("Không tìm thấy tệp."));
        if (a.getOwner() != null && !a.getOwner().equals(owner)) {
            throw new ToolsException("Không tìm thấy tệp.");
        }
        return a;
    }

    /** PATCH /api/tools/files/{id}/name — { name } */
    @PatchMapping("/{id}/name")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rename(
            HttpServletRequest request,
            @PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            String owner = ToolsAuthContext.username(request);
            findOwned(id, owner);
            return ResponseEntity.ok(ApiResponse.success(
                    toMap(storage.rename(id, body.get("name"))), "Đã đổi tên"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Files] rename error id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Đổi tên thất bại."));
        }
    }

    /**
     * PUT /api/tools/files/{id}/content — ghi đè nội dung sau khi sửa bảng
     * tính / tài liệu. Nhận multipart để không phải base64 hoá cả file.
     */
    @PutMapping(value = "/{id}/content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveContent(
            HttpServletRequest request,
            @PathVariable Long id, @RequestPart("file") MultipartFile file) {
        try {
            String owner = ToolsAuthContext.username(request);
            findOwned(id, owner);
            return ResponseEntity.ok(ApiResponse.success(
                    toMap(storage.replaceContent(id, file)), "Đã lưu"));
        } catch (ToolsException e) {
            log.warn("[Files] saveContent {}: {}", id, e.getDetail());
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Files] saveContent error id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Lưu nội dung thất bại."));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Object>> delete(HttpServletRequest request, @PathVariable Long id) {
        try {
            String owner = ToolsAuthContext.username(request);
            findOwned(id, owner);
            storage.delete(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Files] delete error id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa tệp thất bại."));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Đọc nội dung
    // ════════════════════════════════════════════════════════════════

    /** GET /api/tools/files/{id}/text — mã nguồn, csv, md, sql */
    @GetMapping("/{id}/text")
    public ResponseEntity<ApiResponse<Map<String, Object>>> text(HttpServletRequest request, @PathVariable Long id) {
        try {
            String owner = ToolsAuthContext.username(request);
            FileAsset asset = findOwned(id, owner);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ext",     asset.getExt());
            m.put("content", storage.readText(asset));
            return ResponseEntity.ok(ApiResponse.success(m, "OK"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Files] text error id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không đọc được nội dung."));
        }
    }

    /**
     * GET /api/tools/files/{id}/download — ép tải về kèm ĐÚNG tên hiển thị.
     *
     * Dùng filename* (RFC 5987) chứ không chỉ filename= : tên tiếng Việt có dấu
     * qua header ASCII sẽ thành dấu hỏi. filename= giữ lại làm bản dự phòng cho
     * trình duyệt cũ.
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(HttpServletRequest request, @PathVariable Long id) {
        String owner = ToolsAuthContext.username(request);
        FileAsset asset = findOwned(id, owner);
        Path path = storage.pathOf(asset);
        if (!Files.exists(path)) throw new ToolsException("Tệp không còn trên máy chủ.");

        String name  = asset.getOriginalName();
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_");
        String utf8  = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + utf8)
                .contentType(MediaType.parseMediaType(
                        asset.getContentType() != null && !asset.getContentType().isBlank()
                                ? asset.getContentType()
                                : MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .body(new FileSystemResource(path));
    }

    /**
     * GET /api/tools/files/download-zip?ids=1,2,3 — chỉ zip các tệp thuộc về user.
     */
    @GetMapping("/download-zip")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
    downloadZip(HttpServletRequest request, @RequestParam List<Long> ids) {
        String owner = ToolsAuthContext.username(request);
        List<Long> ownedIds = repo.findAllById(ids).stream()
                .filter(a -> a.getOwner() == null || a.getOwner().equals(owner))
                .map(FileAsset::getId)
                .toList();

        String zipName = "files-" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".zip";
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body =
                out -> storage.streamZip(ownedIds, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipName + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }

    /**
     * POST /api/tools/files/delete-batch — body: { "ids": [1,2,3] }
     * Chỉ xóa các id thuộc về user.
     */
    @PostMapping("/delete-batch")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteBatch(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        try {
            String owner = ToolsAuthContext.username(request);
            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) body.getOrDefault("ids", List.of());
            List<Long> ids = raw.stream().map(o -> Long.parseLong(String.valueOf(o))).toList();
            List<Long> ownedIds = repo.findAllById(ids).stream()
                    .filter(a -> a.getOwner() == null || a.getOwner().equals(owner))
                    .map(FileAsset::getId)
                    .toList();
            int n = storage.deleteMany(ownedIds);
            return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", n), "Đã xóa " + n + " tệp"));
        } catch (Exception e) {
            log.error("[Files] deleteBatch error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa hàng loạt thất bại."));
        }
    }

    // ─────────────────────────────────────────────────────────────────

    static Map<String, Object> toMap(FileAsset a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           a.getId());
        m.put("originalName", a.getOriginalName());
        m.put("ext",          a.getExt());
        m.put("kind",         a.getKind());
        m.put("contentType",  a.getContentType());
        m.put("sizeBytes",    a.getSizeBytes());
        m.put("createdAt",    a.getCreatedAt());
        m.put("updatedAt",    a.getUpdatedAt());
        m.put("url",      "/files/" + a.getFileName());
        m.put("thumbUrl", a.getThumbName() != null ? "/files/" + a.getThumbName() : null);
        return m;
    }
}