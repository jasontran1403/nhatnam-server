package com.nhatnam.server.tools.controller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.config.ToolsAuthContext;
import com.nhatnam.server.tools.entity.MediaAlbum;
import com.nhatnam.server.tools.entity.MediaAlbumAsset;
import com.nhatnam.server.tools.entity.MediaAsset;
import com.nhatnam.server.tools.repository.MediaAlbumAssetRepository;
import com.nhatnam.server.tools.repository.MediaAlbumRepository;
import com.nhatnam.server.tools.repository.MediaAssetRepository;
import com.nhatnam.server.tools.service.MediaStorageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thư viện tài nguyên: ảnh và video dùng chung.
 * Base path: /api/tools/media  (công khai, xem SecurityConfiguration)
 *
 * File thật phục vụ tại /media/{fileName} — xem MediaWebConfig.
 */
@RestController
@RequestMapping("/api/tools/media")
@RequiredArgsConstructor
@Log4j2
public class MediaController {

    private final MediaStorageService      storage;
    private final MediaAssetRepository     repo;
    private final MediaAlbumRepository     albumRepo;
    private final MediaAlbumAssetRepository albumAssetRepo;

    /**
     * GET /api/tools/media — mới nhất lên trước.
     * Params (đều tuỳ chọn): page, size, favorite, from, to (epoch millis), q
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "40") int size,
            @RequestParam(required = false) Boolean favorite,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long albumId) {
        try {
            String owner = ToolsAuthContext.username(request);
            Boolean favFilter = Boolean.TRUE.equals(favorite) ? Boolean.TRUE : null;
            String  qFilter   = (q != null && !q.isBlank()) ? q.trim() : null;
            // Mỗi batch tối đa 1000 — FE infinite-scroll tự fetch thêm khi cần
            var pageable = PageRequest.of(page, Math.min(size, 1000));

            var paged = (albumId != null)
                    ? repo.searchByIds(albumAssetRepo.findAssetIdsByAlbumId(albumId),
                                       owner, favFilter, from, to, qFilter, pageable)
                    : repo.search(owner, favFilter, from, to, qFilter, pageable);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content",       paged.getContent().stream().map(MediaController::toMap).toList());
            result.put("totalElements", paged.getTotalElements());
            result.put("totalPages",    paged.getTotalPages());
            result.put("currentPage",   paged.getNumber());
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[Media] list error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không tải được danh sách tài nguyên."));
        }
    }

    /**
     * POST /api/tools/media/upload — multipart, nhận nhiều file cùng lúc.
     * Một file hỏng không làm hỏng cả lô: trả về danh sách thành công + danh sách lỗi.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            HttpServletRequest request,
            @RequestPart("files") List<MultipartFile> files) {
        try {
            String owner = ToolsAuthContext.username(request);
            List<Map<String, Object>> saved  = new java.util.ArrayList<>();
            List<String>              failed = new java.util.ArrayList<>();

            for (MultipartFile f : files) {
                try {
                    saved.add(toMap(storage.store(f, owner)));
                } catch (ToolsException e) {
                    log.warn("[Media] Bỏ qua '{}': {}", f.getOriginalFilename(), e.getDetail());
                    failed.add(f.getOriginalFilename() + " — " + e.getMessage());
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("saved",  saved);
            result.put("failed", failed);
            return ResponseEntity.ok(ApiResponse.success(result,
                    saved.size() + "/" + files.size() + " file đã tải lên"));

        } catch (ToolsException e) {
            log.warn("[Media] upload: {}", e.getDetail());
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Media] upload error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tải file lên thất bại."));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Upload theo từng phần — dùng cho file nặng (video)
    // ════════════════════════════════════════════════════════════════

    /** POST /api/tools/media/chunk/init → { uploadId } */
    @PostMapping("/chunk/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chunkInit() {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("uploadId", storage.initChunkUpload()), "OK"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Media] chunkInit error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không khởi tạo được phiên tải lên."));
        }
    }

    /** POST /api/tools/media/chunk/part — multipart: uploadId, index, chunk */
    @PostMapping(value = "/chunk/part", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Object>> chunkPart(
            @RequestPart("uploadId") String uploadId,
            @RequestPart("index") String index,
            @RequestPart("chunk") MultipartFile chunk) {
        try {
            storage.saveChunk(uploadId, Integer.parseInt(index), chunk);
            return ResponseEntity.ok(ApiResponse.success(null, "OK"));
        } catch (ToolsException e) {
            log.warn("[Media] chunkPart: {}", e.getDetail());
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Media] chunkPart error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tải phần dữ liệu thất bại."));
        }
    }

    /** POST /api/tools/media/chunk/complete — body: uploadId, totalChunks, fileName, contentType */
    @PostMapping("/chunk/complete")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chunkComplete(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        try {
            String owner = ToolsAuthContext.username(request);
            String uploadId    = String.valueOf(body.get("uploadId"));
            int totalChunks    = Integer.parseInt(String.valueOf(body.get("totalChunks")));
            String fileName    = (String) body.get("fileName");
            String contentType = (String) body.get("contentType");

            var asset = storage.completeChunkUpload(uploadId, totalChunks, fileName, contentType, owner);
            return ResponseEntity.ok(ApiResponse.success(toMap(asset), "Đã tải lên"));

        } catch (ToolsException e) {
            log.warn("[Media] chunkComplete: {}", e.getDetail());
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Media] chunkComplete error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Hoàn tất tải lên thất bại."));
        }
    }

    /**
     * PATCH /api/tools/media/{id}/name — đổi tên hiển thị.
     * Body: { "name": "Tên mới" }
     * Đuôi file được giữ nguyên: người dùng đổi tên cho dễ tìm, không phải để
     * đổi định dạng — mất đuôi thì lúc tải về máy không mở được bằng app nào.
     */
    /**
     * Lấy asset và kiểm ownership trong 1 bước. Trả lỗi "không tìm thấy" (chứ
     * không phải "không có quyền") để không tiết lộ id tồn tại cho user khác.
     */
    private MediaAsset findOwned(Long id, String owner) {
        MediaAsset a = repo.findById(id)
                .orElseThrow(() -> new ToolsException("Không tìm thấy tài nguyên."));
        if (a.getOwner() != null && !a.getOwner().equals(owner)) {
            throw new ToolsException("Không tìm thấy tài nguyên.");
        }
        return a;
    }

    @PatchMapping("/{id}/name")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rename(
            HttpServletRequest request,
            @PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            String owner = ToolsAuthContext.username(request);
            String raw = body.get("name");
            if (raw == null || raw.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(400, "Tên không được để trống."));
            }
            MediaAsset asset = findOwned(id, owner);

            asset.setOriginalName(keepExtension(raw.trim(), asset.getOriginalName(), asset.getFileName()));
            return ResponseEntity.ok(ApiResponse.success(toMap(repo.save(asset)), "Đã đổi tên"));

        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Media] rename error id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Đổi tên thất bại."));
        }
    }

    /**
     * PATCH /api/tools/media/{id}/favorite — bật/tắt thả tim.
     * Body: { "favorite": true }
     */
    @PatchMapping("/{id}/favorite")
    public ResponseEntity<ApiResponse<Map<String, Object>>> favorite(
            HttpServletRequest request,
            @PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        try {
            String owner = ToolsAuthContext.username(request);
            MediaAsset asset = findOwned(id, owner);
            asset.setFavorite(Boolean.TRUE.equals(body.get("favorite")));
            return ResponseEntity.ok(ApiResponse.success(toMap(repo.save(asset)), "OK"));

        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Media] favorite error id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Cập nhật thất bại."));
        }
    }

    @PostMapping("/favorite-batch")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> favoriteBatch(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        try {
            String owner = ToolsAuthContext.username(request);
            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) body.getOrDefault("ids", List.of());
            List<Long> ids = raw.stream()
                    .map(o -> Long.parseLong(String.valueOf(o)))
                    .toList();
            boolean favorite = Boolean.TRUE.equals(body.get("favorite"));

            int updated = 0;
            for (Long id : ids) {
                var opt = repo.findById(id);
                if (opt.isPresent()) {
                    MediaAsset asset = opt.get();
                    // Bỏ qua thầm lặng các id không thuộc user — không tiết lộ có tồn tại
                    if (asset.getOwner() != null && !asset.getOwner().equals(owner)) continue;
                    asset.setFavorite(favorite);
                    repo.save(asset);
                    updated++;
                }
            }
            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("updated", updated),
                    "Đã cập nhật " + updated + " mục"));
        } catch (Exception e) {
            log.error("[Media] favoriteBatch error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Cập nhật yêu thích thất bại."));
        }
    }

    /** Giữ nguyên đuôi file cũ nếu tên mới không có đuôi hợp lệ */
    private static String keepExtension(String newName, String oldName, String storedName) {
        String ext = extensionOf(oldName);
        if (ext.isEmpty()) ext = extensionOf(storedName);
        if (ext.isEmpty()) return newName;
        return newName.toLowerCase().endsWith(ext.toLowerCase()) ? newName : newName + ext;
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return (dot > 0 && dot < name.length() - 1) ? name.substring(dot) : "";
    }

    /** DELETE /api/tools/media/{id} */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Object>> delete(HttpServletRequest request, @PathVariable Long id) {
        try {
            String owner = ToolsAuthContext.username(request);
            findOwned(id, owner);   // ném lỗi nếu không thuộc user
            storage.delete(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (ToolsException e) {
            log.warn("[Media] delete {}: {}", id, e.getDetail());
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Media] delete error id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa tài nguyên thất bại."));
        }
    }

    /**
     * GET /api/tools/media/download-zip?ids=1,2,3
     * Chỉ zip các id thuộc về người đang đăng nhập, các id khác bị bỏ qua thầm
     * lặng để không tiết lộ có tồn tại.
     */
    @GetMapping("/download-zip")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
    downloadZip(HttpServletRequest request, @RequestParam java.util.List<Long> ids) {
        String owner = ToolsAuthContext.username(request);
        java.util.List<Long> ownedIds = repo.findAllById(ids).stream()
                .filter(a -> a.getOwner() == null || a.getOwner().equals(owner))
                .map(MediaAsset::getId)
                .toList();

        String zipName = "media-" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".zip";
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body =
                out -> storage.streamZip(ownedIds, out);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + zipName + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }

    /**
     * POST /api/tools/media/delete-batch — body: { "ids": [1,2,3] }
     * Chỉ xóa các id thuộc về người đang đăng nhập.
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
                    .map(MediaAsset::getId)
                    .toList();
            int n = storage.deleteMany(ownedIds);
            return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", n), "Đã xóa " + n + " mục"));
        } catch (Exception e) {
            log.error("[Media] deleteBatch error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa hàng loạt thất bại."));
        }
    }

    /**
     * POST /api/tools/media/regenerate-thumbnails
     * Dựng lại toàn bộ thumbnail ẢNH từ file gốc — dùng MỘT LẦN để sửa các
     * thumbnail cũ bị xoay sai (tạo trước khi backend biết xử lý EXIF).
     * Video được bỏ qua. Trả về số ảnh đã xử lý.
     */
    @PostMapping("/regenerate-thumbnails")
    public ResponseEntity<ApiResponse<Map<String, Object>>> regenerateThumbnails() {
        try {
            int count = storage.regenerateThumbnails();
            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("regenerated", count), "Đã tạo lại " + count + " thumbnail"));
        } catch (Exception e) {
            log.error("[Media] regenerate error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tạo lại thumbnail thất bại."));
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  ALBUM
    // ════════════════════════════════════════════════════════════════

    /** GET /api/tools/media/albums — danh sách album kèm số lượng ảnh */
    @GetMapping("/albums")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listAlbums() {
        try {
            var albums = albumRepo.findAllByOrderByCreatedAtDesc();
            var result = albums.stream().map(a -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",        a.getId());
                m.put("name",      a.getName());
                m.put("count",     albumAssetRepo.countByAlbumId(a.getId()));
                m.put("createdAt", a.getCreatedAt());
                return m;
            }).toList();
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[Media] listAlbums error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không tải được danh sách album."));
        }
    }

    /** POST /api/tools/media/albums — tạo album mới. Body: { "name": "..." } */
    @PostMapping("/albums")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createAlbum(
            @RequestBody Map<String, String> body) {
        try {
            String name = body.get("name");
            if (name == null || name.isBlank())
                return ResponseEntity.ok(ApiResponse.error(400, "Tên album không được để trống."));

            var album = albumRepo.save(MediaAlbum.builder()
                    .name(name.trim())
                    .createdAt(System.currentTimeMillis())
                    .build());

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",        album.getId());
            m.put("name",      album.getName());
            m.put("count",     0L);
            m.put("createdAt", album.getCreatedAt());
            return ResponseEntity.ok(ApiResponse.success(m, "Đã tạo album"));
        } catch (Exception e) {
            log.error("[Media] createAlbum error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tạo album thất bại."));
        }
    }

    /** DELETE /api/tools/media/albums/{id} — xóa album (không xóa ảnh) */
    @DeleteMapping("/albums/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<Object>> deleteAlbum(@PathVariable Long id) {
        try {
            albumAssetRepo.deleteAllByAlbumId(id);
            albumRepo.deleteById(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa album"));
        } catch (Exception e) {
            log.error("[Media] deleteAlbum error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa album thất bại."));
        }
    }

    /** PATCH /api/tools/media/albums/{id}/name — đổi tên album */
    @PatchMapping("/albums/{id}/name")
    public ResponseEntity<ApiResponse<Object>> renameAlbum(
            @PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            String name = body.get("name");
            if (name == null || name.isBlank())
                return ResponseEntity.ok(ApiResponse.error(400, "Tên không được để trống."));
            var album = albumRepo.findById(id)
                    .orElseThrow(() -> new ToolsException("Không tìm thấy album."));
            album.setName(name.trim());
            albumRepo.save(album);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã đổi tên"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Media] renameAlbum error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Đổi tên thất bại."));
        }
    }

    /**
     * POST /api/tools/media/albums/{id}/add — thêm ảnh vào album.
     * Body: { "assetIds": [1,2,3] }
     */
    @PostMapping("/albums/{id}/add")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> addToAlbum(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            albumRepo.findById(id)
                    .orElseThrow(() -> new ToolsException("Không tìm thấy album."));

            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) body.getOrDefault("assetIds", List.of());
            List<Long> assetIds = raw.stream().map(o -> Long.parseLong(String.valueOf(o))).toList();

            int added = 0;
            long now = System.currentTimeMillis();
            for (Long assetId : assetIds) {
                if (!albumAssetRepo.existsByAlbumIdAndAssetId(id, assetId)) {
                    albumAssetRepo.save(MediaAlbumAsset.builder()
                            .albumId(id).assetId(assetId).addedAt(now).build());
                    added++;
                }
            }
            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("added", added), "Đã thêm " + added + " mục vào album"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Media] addToAlbum error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Thêm vào album thất bại."));
        }
    }

    /**
     * POST /api/tools/media/albums/{id}/remove — gỡ ảnh khỏi album.
     * Body: { "assetIds": [1,2,3] }
     */
    @PostMapping("/albums/{id}/remove")
    @Transactional
    public ResponseEntity<ApiResponse<Object>> removeFromAlbum(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) body.getOrDefault("assetIds", List.of());
            List<Long> assetIds = raw.stream().map(o -> Long.parseLong(String.valueOf(o))).toList();
            albumAssetRepo.deleteAllByAlbumIdAndAssetIdIn(id, assetIds);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã gỡ khỏi album"));
        } catch (Exception e) {
            log.error("[Media] removeFromAlbum error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Gỡ khỏi album thất bại."));
        }
    }

    // ─────────────────────────────────────────────────────────────────

    static Map<String, Object> toMap(MediaAsset a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           a.getId());
        m.put("mediaType",    a.getMediaType());
        m.put("originalName", a.getOriginalName());
        m.put("contentType",  a.getContentType());
        m.put("sizeBytes",    a.getSizeBytes());
        m.put("source",       a.getSource());
        m.put("favorite",     Boolean.TRUE.equals(a.getFavorite()));
        m.put("createdAt",    a.getCreatedAt());

        // File HEIC/HEIF trên đĩa trình duyệt không render được → dùng thumbnail
        // (đã convert sang JPEG) cho cả url lẫn thumbUrl. File mới sẽ tự convert
        // sang JPEG khi upload nhờ persistFile, nhưng file cũ vẫn là .heic trên đĩa.
        String fn = a.getFileName() != null ? a.getFileName().toLowerCase(java.util.Locale.ROOT) : "";
        boolean heicOnDisk = fn.endsWith(".heic") || fn.endsWith(".heif");

        String thumbPath = a.getThumbName() != null
                ? "/media/" + a.getThumbName()
                : "/media/" + a.getFileName();
        m.put("thumbUrl", thumbPath);
        m.put("url", heicOnDisk ? thumbPath : "/media/" + a.getFileName());
        return m;
    }
}