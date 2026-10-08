package com.nhatnam.server.tools.service;

import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.entity.FileAsset;
import com.nhatnam.server.tools.repository.FileAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kho Tệp: lưu file bất kỳ lên đĩa, ghi metadata vào DB, chống trùng tên.
 *
 * Tách hẳn khỏi MediaStorageService vì hai kho có luật khác nhau:
 *   • Media  — chỉ ảnh/video, tên hiển thị được phép trùng (gallery không quan tâm).
 *   • Files  — mọi loại file, tên hiển thị là danh tính của file nên phải duy nhất.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class FileStorageService {

    private static final int  THUMB_MAX_EDGE = 480;
    private static final long THUMB_TIMEOUT_SECONDS = 60;

    /** Trần dung lượng đọc nội dung dạng chữ về client (mã nguồn, csv, md, sql) */
    private static final long TEXT_PREVIEW_LIMIT = 5L * 1024 * 1024;

    private final FileAssetRepository repo;

    @Value("${tools.files.storage-dir:./data/files}")
    private String storageDir;

    // ════════════════════════════════════════════════════════════════
    // Thư mục
    // ════════════════════════════════════════════════════════════════

    public Path storageRoot() {
        Path root = Path.of(storageDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new ToolsException(
                    "Máy chủ chưa sẵn sàng lưu tệp. Vui lòng liên hệ quản trị hệ thống.",
                    "Không tạo được thư mục lưu tệp: " + root + " — " + e.getMessage(), e);
        }
        return root;
    }

    public Path pathOf(FileAsset asset) {
        return storageRoot().resolve(asset.getFileName());
    }

    // ════════════════════════════════════════════════════════════════
    // Chống trùng tên
    // ════════════════════════════════════════════════════════════════

    /** Kết quả đặt tên: tên chốt lại và có bị đổi so với mong muốn hay không */
    public record NameResult(String finalName, boolean renamed, String requestedName) {}

    /**
     * Chốt tên hiển thị cho file.
     *
     * Luật: coi TOÀN BỘ chuỗi người dùng nhập là phần gốc, nếu bị chiếm thì nối
     * thêm " (n)" với n là số nhỏ nhất còn trống.
     *
     *   Đã có ABC, ABC (1), ABC (2)  →  nhập "ABC"      →  ABC (3)
     *   Đã có ABC (2)                →  nhập "ABC (2)"  →  ABC (2) (1)  + cảnh báo
     *
     * Trường hợp thứ hai trông lạ mắt nhưng đúng ý: người dùng gõ đúng tên một
     * file đang tồn tại thì phải báo cho họ biết là đã trùng, chứ không âm thầm
     * nhét vào khe (3) như thể họ định đánh số tiếp.
     *
     * @param desired    tên người dùng nhập, có hoặc không có đuôi
     * @param ext        đuôi chuẩn lấy từ file gốc (không dấu chấm), có thể rỗng
     * @param excludeId  bỏ qua chính file này khi đổi tên (null khi tạo mới)
     */
    public NameResult resolveName(String desired, String ext, Long excludeId) {
        String base = stripExtension(desired == null ? "" : desired.trim(), ext);
        base = sanitizeName(base);
        if (base.isBlank()) base = "tep-khong-ten";

        String suffix = (ext == null || ext.isBlank()) ? "" : "." + ext.toLowerCase(Locale.ROOT);
        String wanted = base + suffix;

        Set<String> taken = takenNames(base, excludeId);
        if (!taken.contains(wanted.toLowerCase(Locale.ROOT))) {
            return new NameResult(wanted, false, wanted);
        }

        for (int n = 1; n < 10_000; n++) {
            String candidate = base + " (" + n + ")" + suffix;
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) {
                return new NameResult(candidate, true, wanted);
            }
        }
        // Gần như không thể xảy ra; vẫn phải có lối thoát để không treo vòng lặp
        return new NameResult(base + " (" + System.currentTimeMillis() + ")" + suffix, true, wanted);
    }

    /** Tập tên (viết thường) đang bị chiếm, chỉ những tên bắt đầu bằng base */
    private Set<String> takenNames(String base, Long excludeId) {
        List<String> rows = repo.findNamesStartingWith(base);
        Set<String> set = new HashSet<>();
        for (String n : rows) if (n != null) set.add(n.toLowerCase(Locale.ROOT));

        if (excludeId != null) {
            repo.findById(excludeId).ifPresent(f -> {
                if (f.getOriginalName() != null) set.remove(f.getOriginalName().toLowerCase(Locale.ROOT));
            });
        }
        return set;
    }

    /**
     * Bỏ đuôi khỏi tên người dùng nhập.
     * Chỉ cắt đúng đuôi thật của file — nếu cắt bừa mọi thứ sau dấu chấm cuối
     * thì "Báo cáo v1.2" sẽ mất phần ".2".
     */
    private static String stripExtension(String name, String ext) {
        if (ext == null || ext.isBlank()) return name;
        String dotted = "." + ext.toLowerCase(Locale.ROOT);
        return name.toLowerCase(Locale.ROOT).endsWith(dotted)
                ? name.substring(0, name.length() - dotted.length())
                : name;
    }

    /** Loại ký tự không hợp lệ cho tên file trên Windows/macOS và gom khoảng trắng */
    private static String sanitizeName(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // ════════════════════════════════════════════════════════════════
    // Ghi file
    // ════════════════════════════════════════════════════════════════

    /**
     * Lưu file từ một đường dẫn tạm. File nguồn được DI CHUYỂN nên caller
     * không cần dọn nữa.
     *
     * @param desiredName tên người dùng muốn; để trống thì lấy tên file gốc
     * @param owner       username của người upload — dùng để lọc list theo user
     */
    public FileAsset persist(Path source, String uploadName, String desiredName, String contentType, String owner) {
        Path root = storageRoot();
        String ext = extensionOf(uploadName);
        String kind = detectKind(ext, contentType);

        String wanted = (desiredName != null && !desiredName.isBlank())
                ? desiredName.trim()
                : stripExtension(uploadName == null ? "" : uploadName, ext);
        NameResult name = resolveName(wanted, ext, null);

        String stored = UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
        Path target = root.resolve(stored);

        long size;
        try {
            size = Files.size(source);
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException crossDevice) {
                // Thư mục tạm của hệ điều hành có thể nằm khác ổ đĩa với kho tệp
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                deleteQuietly(source);
            }
        } catch (IOException e) {
            throw new ToolsException("Lưu tệp thất bại.",
                    "Không ghi được " + target + ": " + e.getMessage(), e);
        }

        FileAsset saved = repo.save(FileAsset.builder()
                .fileName(stored)
                .originalName(name.finalName())
                .ext(ext)
                .kind(kind)
                .contentType(contentType)
                .sizeBytes(size)
                .thumbName(buildThumbnail(target, kind))
                .owner(owner)
                .createdAt(System.currentTimeMillis())
                .updatedAt(System.currentTimeMillis())
                .build());

        if (name.renamed()) {
            log.info("[Files] Trùng tên '{}' → lưu thành '{}'", name.requestedName(), name.finalName());
        }
        return saved;
    }

    /**
     * Ghi đè nội dung của một tệp đã có — dùng khi người dùng sửa bảng tính /
     * tài liệu rồi bấm Lưu.
     *
     * Ghi ra file tạm cùng thư mục rồi ATOMIC_MOVE đè lên file cũ: nếu đứt giữa
     * chừng thì file cũ vẫn nguyên vẹn, thay vì thành file rỗng.
     */
    public FileAsset replaceContent(Long id, MultipartFile file) {
        FileAsset asset = repo.findById(id)
                .orElseThrow(() -> new ToolsException("Không tìm thấy tệp."));
        if (file == null || file.isEmpty()) {
            throw new ToolsException("Nội dung gửi lên đang trống.");
        }

        Path target = pathOf(asset);
        Path temp = null;
        try {
            temp = Files.createTempFile(target.getParent(), "save_", ".tmp");
            file.transferTo(temp.toFile());
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;

            asset.setSizeBytes(Files.size(target));
            asset.setUpdatedAt(System.currentTimeMillis());
            // Ảnh thu nhỏ cũ không còn khớp nội dung mới
            if (asset.getThumbName() != null) {
                deleteQuietly(storageRoot().resolve(asset.getThumbName()));
            }
            asset.setThumbName(buildThumbnail(target, asset.getKind()));
            return repo.save(asset);

        } catch (IOException e) {
            throw new ToolsException("Lưu nội dung thất bại.",
                    "Không ghi đè được " + target + ": " + e.getMessage(), e);
        } finally {
            deleteQuietly(temp);
        }
    }

    public FileAsset rename(Long id, String desired) {
        FileAsset asset = repo.findById(id)
                .orElseThrow(() -> new ToolsException("Không tìm thấy tệp."));
        if (desired == null || desired.isBlank()) {
            throw new ToolsException("Tên không được để trống.");
        }
        NameResult name = resolveName(desired, asset.getExt(), id);
        asset.setOriginalName(name.finalName());
        asset.setUpdatedAt(System.currentTimeMillis());
        return repo.save(asset);
    }

    public void delete(Long id) {
        FileAsset asset = repo.findById(id)
                .orElseThrow(() -> new ToolsException("Không tìm thấy tệp."));
        deleteQuietly(pathOf(asset));
        if (asset.getThumbName() != null) deleteQuietly(storageRoot().resolve(asset.getThumbName()));
        repo.delete(asset);
    }

    /** Nén nhiều tệp thành một luồng zip (stream, không giữ hết vào RAM). */
    public void streamZip(List<Long> ids, OutputStream out) throws IOException {
        Set<String> used = new HashSet<>();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (FileAsset a : repo.findAllById(ids)) {
                Path p = pathOf(a);
                if (!Files.exists(p)) continue;
                String name = (a.getOriginalName() != null && !a.getOriginalName().isBlank())
                        ? a.getOriginalName() : a.getFileName();
                zos.putNextEntry(new ZipEntry(uniqueEntry(name, used)));
                Files.copy(p, zos);
                zos.closeEntry();
            }
            zos.finish();
        }
    }

    /** Xóa nhiều tệp; bỏ qua tệp lỗi. Trả về số đã xóa. */
    public int deleteMany(List<Long> ids) {
        int n = 0;
        for (Long id : ids) {
            try { delete(id); n++; }
            catch (Exception e) { log.warn("[Files] Xóa lỗi id={}: {}", id, e.getMessage()); }
        }
        return n;
    }

    private static String uniqueEntry(String name, Set<String> used) {
        if (used.add(name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext  = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (used.add(candidate)) return candidate;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Tải lên theo từng phần
    // ════════════════════════════════════════════════════════════════

    private Path chunkDir(String uploadId) {
        if (uploadId == null || !uploadId.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new ToolsException("Phiên tải lên không hợp lệ.");
        }
        Path dir = storageRoot().resolve(".chunks").resolve(uploadId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new ToolsException("Không tạo được vùng tạm để tải lên.",
                    "Không tạo được " + dir + ": " + e.getMessage(), e);
        }
        return dir;
    }

    public String initChunkUpload() {
        String uploadId = UUID.randomUUID().toString().replace("-", "");
        chunkDir(uploadId);
        return uploadId;
    }

    public void saveChunk(String uploadId, int index, MultipartFile part) {
        if (index < 0) throw new ToolsException("Số thứ tự phần không hợp lệ.");
        Path dest = chunkDir(uploadId).resolve(String.valueOf(index));
        try {
            part.transferTo(dest.toFile());
        } catch (IOException e) {
            throw new ToolsException("Tải phần " + index + " thất bại.",
                    "Không ghi được chunk " + dest + ": " + e.getMessage(), e);
        }
    }

    public FileAsset completeChunkUpload(String uploadId, int totalChunks,
                                         String uploadName, String desiredName, String contentType, String owner) {
        Path dir = chunkDir(uploadId);
        Path merged = null;
        try {
            merged = Files.createTempFile("merged_", ".tmp");
            try (var out = Files.newOutputStream(merged)) {
                for (int i = 0; i < totalChunks; i++) {
                    Path part = dir.resolve(String.valueOf(i));
                    if (!Files.exists(part)) {
                        throw new ToolsException("Tải lên bị thiếu dữ liệu, vui lòng thử lại.",
                                "Thiếu chunk " + i + "/" + totalChunks + " của " + uploadId);
                    }
                    Files.copy(part, out);
                }
            }
            return persist(merged, uploadName, desiredName, contentType, owner);

        } catch (ToolsException e) {
            deleteQuietly(merged);
            throw e;
        } catch (IOException e) {
            deleteQuietly(merged);
            throw new ToolsException("Ghép tệp tải lên thất bại.",
                    "Lỗi ghép chunk của " + uploadId + ": " + e.getMessage(), e);
        } finally {
            deleteDirQuietly(dir);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Đọc
    // ════════════════════════════════════════════════════════════════

    public Page<FileAsset> search(String owner, String q, Long from, Long to, List<String> exts,
                                  int page, int size, String sortField, String direction) {
        Sort sort = Sort.by(
                "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC,
                switch (sortField == null ? "" : sortField) {
                    case "name" -> "originalName";
                    case "size" -> "sizeBytes";
                    default     -> "createdAt";
                });
        // Chốt thêm id để hai file cùng mốc thời gian không đổi chỗ giữa các trang,
        // gây ra hiện tượng lặp/mất bản ghi khi cuộn tải thêm.
        sort = sort.and(Sort.by(Sort.Direction.DESC, "id"));

        List<String> normalized = (exts == null || exts.isEmpty())
                ? null
                : exts.stream().map(s -> s.toLowerCase(Locale.ROOT).replace(".", "").trim())
                .filter(s -> !s.isEmpty()).toList();
        if (normalized != null && normalized.isEmpty()) normalized = null;

        return repo.search(
                owner,
                (q != null && !q.isBlank()) ? q.trim() : null,
                from, to, normalized,
                PageRequest.of(page, Math.min(Math.max(size, 1), 100), sort));
    }

    /** Nội dung dạng chữ cho preview mã nguồn / csv / md / sql */
    public String readText(FileAsset asset) {
        Path p = pathOf(asset);
        try {
            long size = Files.size(p);
            if (size > TEXT_PREVIEW_LIMIT) {
                throw new ToolsException("Tệp quá lớn để xem trực tiếp (giới hạn 5 MB).");
            }
            byte[] bytes = Files.readAllBytes(p);
            return decodeText(bytes);
        } catch (IOException e) {
            throw new ToolsException("Không đọc được nội dung tệp.",
                    "Lỗi đọc " + p + ": " + e.getMessage(), e);
        }
    }

    /**
     * Đoán bảng mã. File do Excel Việt Nam xuất ra thường là UTF-8 có BOM hoặc
     * Windows-1258; đọc cứng UTF-8 sẽ ra một màn hình ký tự hỏng. Thử UTF-8
     * nghiêm ngặt trước, hỏng thì lùi về windows-1258.
     */
    private static String decodeText(byte[] bytes) {
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;   // bỏ BOM, không thì ký tự đầu tiên hiện ra là
        }
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder();
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (Exception notUtf8) {
            return new String(bytes, offset, bytes.length - offset,
                    java.nio.charset.Charset.forName("windows-1258"));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Phân loại + thumbnail
    // ════════════════════════════════════════════════════════════════

    private static final Set<String> IMAGE_EXT =
            Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "avif");
    private static final Set<String> VIDEO_EXT =
            Set.of("mp4", "mov", "m4v", "avi", "mkv", "webm");
    private static final Set<String> SHEET_EXT =
            Set.of("xlsx", "xls", "xlsm", "csv", "tsv");
    private static final Set<String> DOC_EXT =
            Set.of("docx", "doc", "odt", "rtf");
    private static final Set<String> CODE_EXT =
            Set.of("java", "js", "jsx", "ts", "tsx", "html", "htm", "css", "scss",
                    "py", "sql", "json", "xml", "yml", "yaml", "sh", "kt", "go",
                    "rs", "php", "rb", "c", "cpp", "h", "cs", "vue", "svelte");
    private static final Set<String> TEXT_EXT =
            Set.of("md", "markdown", "txt", "log", "csv2", "env", "ini", "conf");
    private static final Set<String> ARCHIVE_EXT =
            Set.of("zip", "rar", "7z", "tar", "gz", "bz2");

    public static String detectKind(String ext, String contentType) {
        String e = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        if (IMAGE_EXT.contains(e))   return "IMAGE";
        if (VIDEO_EXT.contains(e))   return "VIDEO";
        if ("pdf".equals(e))         return "PDF";
        if (SHEET_EXT.contains(e))   return "SHEET";
        if (DOC_EXT.contains(e))     return "DOC";
        if (CODE_EXT.contains(e))    return "CODE";
        if (TEXT_EXT.contains(e))    return "TEXT";
        if (ARCHIVE_EXT.contains(e)) return "ARCHIVE";

        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("image/")) return "IMAGE";
        if (ct.startsWith("video/")) return "VIDEO";
        if (ct.startsWith("text/"))  return "TEXT";
        if (ct.contains("pdf"))      return "PDF";
        return "OTHER";
    }

    public static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,12}") ? ext : "";
    }

    /** Ảnh/video mới có thumbnail; các loại khác frontend vẽ biểu tượng theo đuôi */
    private String buildThumbnail(Path source, String kind) {
        if (!"IMAGE".equals(kind) && !"VIDEO".equals(kind)) return null;

        String thumbName = source.getFileName().toString().replaceAll("\\.[^.]+$", "") + "_thumb.jpg";
        Path thumb = source.getParent().resolve(thumbName);
        try {
            if ("VIDEO".equals(kind)) grabVideoFrame(source, thumb);
            else                      scaleImage(source, thumb);
            return Files.exists(thumb) ? thumbName : null;
        } catch (Exception e) {
            log.warn("[Files] Không tạo được thumbnail cho {}: {}", source.getFileName(), e.getMessage());
            deleteQuietly(thumb);
            return null;
        }
    }

    private void scaleImage(Path source, Path thumb) throws IOException {
        BufferedImage img = ImageIO.read(source.toFile());
        if (img == null) throw new IOException("Không đọc được ảnh");

        int w = img.getWidth(), h = img.getHeight();
        double ratio = Math.min(1.0, (double) THUMB_MAX_EDGE / Math.max(w, h));
        int tw = Math.max(1, (int) Math.round(w * ratio));
        int th = Math.max(1, (int) Math.round(h * ratio));

        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, tw, th);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, tw, th, null);
        g.dispose();
        ImageIO.write(out, "jpg", thumb.toFile());
    }

    private void grabVideoFrame(Path source, Path thumb) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-ss", "1", "-i", source.toString(),
                "-frames:v", "1",
                "-vf", "scale='min(" + THUMB_MAX_EDGE + ",iw)':-2",
                "-y", thumb.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (InputStream is = p.getInputStream()) { is.readAllBytes(); }
        if (!p.waitFor(THUMB_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("ffmpeg treo khi tạo thumbnail");
        }
        if (p.exitValue() != 0) throw new IOException("ffmpeg exit " + p.exitValue());
    }

    // ════════════════════════════════════════════════════════════════

    private static final Pattern SUFFIX = Pattern.compile("^(.*) \\((\\d+)\\)$");

    /** Tách "ABC (2)" → base "ABC", số 2. Dùng cho báo cáo/chẩn đoán. */
    public static Optional<Map.Entry<String, Integer>> splitSuffix(String base) {
        Matcher m = SUFFIX.matcher(base);
        return m.matches()
                ? Optional.of(Map.entry(m.group(1), Integer.parseInt(m.group(2))))
                : Optional.empty();
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
    }

    private static void deleteDirQuietly(Path dir) {
        if (dir == null) return;
        try (var stream = Files.list(dir)) {
            stream.forEach(FileStorageService::deleteQuietly);
        } catch (Exception ignored) {}
        deleteQuietly(dir);
    }
}