package com.nhatnam.server.tools.service;

import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.entity.MediaAsset;
import com.nhatnam.server.tools.repository.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Lưu ảnh/video vào thư mục chung trên đĩa và ghi metadata vào DB.
 *
 * Thư mục cấu hình bởi tools.media.storage-dir (mặc định ./data/media).
 * File được phục vụ qua static resource handler ở /media/** — xem MediaWebConfig.
 *
 * Mỗi file đều sinh kèm một ảnh thu nhỏ để lưới gallery không phải tải file gốc
 * (quan trọng khi dùng trên điện thoại: 30 ảnh 5MB = 150MB dữ liệu di động).
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class MediaStorageService {

    private static final int  THUMB_MAX_EDGE = 480;
    private static final long THUMB_TIMEOUT_SECONDS = 60;

    private final MediaAssetRepository repo;

    @Value("${tools.media.storage-dir:./data/media}")
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
                    "Máy chủ chưa sẵn sàng lưu tài nguyên. Vui lòng liên hệ quản trị hệ thống.",
                    "Không tạo được thư mục lưu file: " + root + " — " + e.getMessage(), e);
        }
        return root;
    }

    // ════════════════════════════════════════════════════════════════
    // Lưu file
    // ════════════════════════════════════════════════════════════════

    /** Lưu file người dùng tải lên. owner = username đang đăng nhập, dùng để lọc khi list. */
    public MediaAsset store(MultipartFile file, String owner) {
        if (file == null || file.isEmpty()) {
            throw new ToolsException("Chưa chọn file để tải lên.");
        }
        String contentType = file.getContentType();
        String original    = file.getOriginalFilename();
        String mediaType   = detectMediaType(contentType, original);

        // Ghi ra file tạm rồi di chuyển, KHÔNG readAllBytes():
        // video vài trăm MB nạp hết vào RAM là hết bộ nhớ heap.
        Path temp = null;
        try {
            temp = Files.createTempFile("upload_", ".tmp");
            file.transferTo(temp.toFile());
            return persistFile(temp, original, contentType, mediaType, "UPLOAD", owner);
        } catch (IOException e) {
            throw new ToolsException("Tải file lên thất bại.",
                    "Không đọc được file tải lên: " + e.getMessage(), e);
        } finally {
            deleteQuietly(temp);
        }
    }

    /**
     * Lưu từ một file đã có sẵn trên đĩa (dùng cho upload theo từng phần).
     * File nguồn được DI CHUYỂN nên caller không cần xóa nữa.
     * @param owner username đã đăng nhập — null cho luồng nội bộ không có user (nếu có)
     */
    public MediaAsset persistFile(Path source, String originalName,
                                  String contentType, String mediaType, String sourceTag, String owner) {
        Path root = storageRoot();

        // ── HEIC/HEIF → chuyển sang JPEG để mọi trình duyệt hiển thị được ──
        if ("IMAGE".equals(mediaType) && isHeic(originalName, contentType)) {
            Path converted = null;
            try {
                converted = Files.createTempFile("heic_", ".jpg");
                convertHeicToJpeg(source, converted);
                // Dùng file JPEG thay cho file gốc
                deleteQuietly(source);
                source = converted;
                converted = null;                 // đã chuyển quyền sở hữu, không xóa ở finally
                contentType = "image/jpeg";
                if (originalName != null) {
                    originalName = originalName.replaceAll("(?i)\\.hei[cf]$", ".jpg");
                }
            } catch (Exception e) {
                log.warn("[Media] Không chuyển được HEIC sang JPEG, giữ nguyên file gốc: {}", e.getMessage());
                deleteQuietly(converted);
                // Tiếp tục lưu file HEIC gốc — thumbnail vẫn có thể thành công
            }
        }

        String ext = extensionOf(originalName, contentType, mediaType);
        String fileName = UUID.randomUUID() + ext;
        Path target = root.resolve(fileName);

        long size;
        try {
            size = Files.size(source);
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException crossDevice) {
                // Thư mục tạm và thư mục lưu trữ có thể nằm khác ổ đĩa → move thất bại
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                deleteQuietly(source);
            }
        } catch (IOException e) {
            throw new ToolsException("Lưu file thất bại.",
                    "Không ghi được file " + target + ": " + e.getMessage(), e);
        }

        return repo.save(MediaAsset.builder()
                .fileName(fileName)
                .thumbName(buildThumbnail(target, mediaType))
                .originalName(originalName != null ? originalName : fileName)
                .mediaType(mediaType)
                .contentType(contentType)
                .sizeBytes(size)
                .source(sourceTag)
                .favorite(false)
                .owner(owner)
                .createdAt(System.currentTimeMillis())
                .build());
    }

    // ════════════════════════════════════════════════════════════════
    // Tải/xóa nhiều mục cùng lúc
    // ════════════════════════════════════════════════════════════════

    /** Nén các file gốc của danh sách id thành một luồng zip (không giữ hết vào RAM). */
    public void streamZip(List<Long> ids, OutputStream out) throws IOException {
        Path root = storageRoot();
        Set<String> used = new HashSet<>();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (MediaAsset a : repo.findAllById(ids)) {
                Path p = root.resolve(a.getFileName());
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

    /** Xóa nhiều mục; bỏ qua mục lỗi để không chặn cả lô. Trả về số đã xóa. */
    public int deleteMany(List<Long> ids) {
        int n = 0;
        for (Long id : ids) {
            try { delete(id); n++; }
            catch (Exception e) { log.warn("[Media] Xóa lỗi id={}: {}", id, e.getMessage()); }
        }
        return n;
    }

    /** Tránh trùng tên trong file zip: a.jpg, a (1).jpg, a (2).jpg... */
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
    // Tạo lại thumbnail cho ảnh CŨ (sửa các ảnh đã lỡ tạo thumbnail sai chiều)
    // ════════════════════════════════════════════════════════════════

    /**
     * Quét toàn bộ ẢNH trong DB và dựng lại thumbnail từ file gốc — dùng để
     * chỉnh các thumbnail cũ bị xoay (tạo trước khi có xử lý EXIF). Video bỏ qua.
     * @return số ảnh đã xử lý
     */
    public int regenerateThumbnails() {
        Path root = storageRoot();
        int done = 0;
        for (MediaAsset a : repo.findAll()) {
            if ("VIDEO".equals(a.getMediaType())) continue;
            Path source = root.resolve(a.getFileName());
            if (!Files.exists(source)) continue;
            try {
                String thumb = buildThumbnail(source, a.getMediaType());
                if (thumb != null && !thumb.equals(a.getThumbName())) {
                    a.setThumbName(thumb);
                    repo.save(a);
                }
                done++;
            } catch (Exception e) {
                log.warn("[Media] Tạo lại thumbnail lỗi cho {}: {}", a.getFileName(), e.getMessage());
            }
        }
        log.info("[Media] Đã tạo lại thumbnail cho {} ảnh", done);
        return done;
    }

    // ════════════════════════════════════════════════════════════════
    // Upload theo từng phần (chunk)
    // ════════════════════════════════════════════════════════════════

    /**
     * Video nặng gửi một lần rất dễ hỏng: mạng di động rớt giữa chừng là mất
     * trắng, và còn vướng giới hạn max-file-size của Spring. Chia nhỏ thì mỗi
     * request chỉ vài MB, báo tiến độ mượt và không đụng giới hạn nào.
     *
     * Các phần được ghi vào {storage}/.chunks/{uploadId}/{index}.
     */
    private Path chunkDir(String uploadId) {
        // Chặn uploadId chứa ".." hay dấu / để không ghi ra ngoài thư mục
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

    /** Ghép các phần lại theo đúng thứ tự rồi lưu thành một file hoàn chỉnh. */
    public MediaAsset completeChunkUpload(String uploadId, int totalChunks,
                                          String originalName, String contentType, String owner) {
        Path dir = chunkDir(uploadId);
        String mediaType = detectMediaType(contentType, originalName);
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
            return persistFile(merged, originalName, contentType, mediaType, "UPLOAD", owner);

        } catch (ToolsException e) {
            deleteQuietly(merged);
            throw e;
        } catch (IOException e) {
            deleteQuietly(merged);
            throw new ToolsException("Ghép file tải lên thất bại.",
                    "Lỗi ghép chunk của " + uploadId + ": " + e.getMessage(), e);
        } finally {
            deleteDirQuietly(dir);
        }
    }

    /** Lưu kết quả do hệ thống sinh ra (ảnh/video đã gắn watermark) */
    public MediaAsset storeBytes(byte[] data, String originalName, String contentType, String mediaType, String owner) {
        return persist(data, originalName, contentType, mediaType, "WATERMARK", owner);
    }

    private MediaAsset persist(byte[] data, String originalName,
                               String contentType, String mediaType, String source, String owner) {
        Path root = storageRoot();
        String ext = extensionOf(originalName, contentType, mediaType);
        String fileName = UUID.randomUUID() + ext;

        Path target = root.resolve(fileName);
        try {
            Files.write(target, data);
        } catch (IOException e) {
            throw new ToolsException("Lưu file thất bại.",
                    "Không ghi được file " + target + ": " + e.getMessage(), e);
        }

        String thumbName = buildThumbnail(target, mediaType);

        MediaAsset asset = MediaAsset.builder()
                .fileName(fileName)
                .thumbName(thumbName)
                .originalName(originalName != null ? originalName : fileName)
                .mediaType(mediaType)
                .contentType(contentType)
                .sizeBytes((long) data.length)
                .source(source)
                .owner(owner)
                .createdAt(System.currentTimeMillis())
                .build();

        return repo.save(asset);
    }

    // ════════════════════════════════════════════════════════════════
    // Xóa
    // ════════════════════════════════════════════════════════════════

    public void delete(Long id) {
        MediaAsset asset = repo.findById(id)
                .orElseThrow(() -> new ToolsException("Không tìm thấy tài nguyên."));
        Path root = storageRoot();
        deleteQuietly(root.resolve(asset.getFileName()));
        if (asset.getThumbName() != null) deleteQuietly(root.resolve(asset.getThumbName()));
        repo.delete(asset);
    }

    // ════════════════════════════════════════════════════════════════
    // Thumbnail
    // ════════════════════════════════════════════════════════════════

    /**
     * Ảnh → thu nhỏ bằng ImageIO. Video → lấy 1 khung hình bằng ffmpeg.
     * Thất bại thì trả null, frontend sẽ dùng file gốc — chậm hơn nhưng vẫn chạy,
     * không đáng để chặn cả thao tác tải lên.
     */
    private String buildThumbnail(Path source, String mediaType) {
        String thumbName = source.getFileName().toString().replaceAll("\\.[^.]+$", "") + "_thumb.jpg";
        Path thumb = source.getParent().resolve(thumbName);
        try {
            if ("VIDEO".equals(mediaType)) {
                grabVideoFrame(source, thumb);
            } else {
                scaleImage(source, thumb);
            }
            return Files.exists(thumb) ? thumbName : null;
        } catch (Exception e) {
            log.warn("[Media] Không tạo được thumbnail cho {}: {}", source.getFileName(), e.getMessage());
            deleteQuietly(thumb);
            return null;
        }
    }

    /**
     * Chuyển HEIC/HEIF sang JPEG bằng ffmpeg.
     * ffmpeg đọc được HEIC nhờ codec libheif (có sẵn trên hầu hết bản cài).
     */
    private void convertHeicToJpeg(Path source, Path dest) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-i", source.toString(),
                "-q:v", "2",           // chất lượng JPEG cao
                "-y", dest.toString());
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            try (InputStream is = p.getInputStream()) { is.readAllBytes(); }
            if (!p.waitFor(THUMB_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("ffmpeg treo khi chuyển HEIC");
            }
            if (p.exitValue() != 0) throw new IOException("ffmpeg exit " + p.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Bị gián đoạn khi chuyển HEIC", e);
        }
    }

    /**
     * Tạo thumbnail cho ảnh bằng ffmpeg — dùng khi ImageIO không đọc được
     * (HEIC/HEIF hoặc định dạng lạ).
     */
    private void scaleImageViaFfmpeg(Path source, Path thumb) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-i", source.toString(),
                "-vf", "scale='min(" + THUMB_MAX_EDGE + ",iw)':-2",
                "-q:v", "4",
                "-y", thumb.toString());
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            try (InputStream is = p.getInputStream()) { is.readAllBytes(); }
            if (!p.waitFor(THUMB_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("ffmpeg treo khi tạo thumbnail ảnh");
            }
            if (p.exitValue() != 0) throw new IOException("ffmpeg exit " + p.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Bị gián đoạn khi tạo thumbnail", e);
        }
    }

    private void scaleImage(Path source, Path thumb) throws IOException {
        BufferedImage img = ImageIO.read(source.toFile());
        // ImageIO không hỗ trợ HEIC/HEIF → dùng ffmpeg làm thumbnail
        if (img == null) {
            scaleImageViaFfmpeg(source, thumb);
            return;
        }

        // ImageIO KHÔNG áp dụng cờ xoay EXIF (ảnh chụp từ điện thoại hay bị xoay
        // 90°/180°). Đọc orientation rồi xoay lại cho đúng TRƯỚC khi thu nhỏ,
        // vì thumbnail xuất ra dạng JPEG mới sẽ mất sạch EXIF.
        int orientation = readExifOrientation(source);
        img = applyOrientation(img, orientation);

        int w = img.getWidth(), h = img.getHeight();
        double ratio = (double) THUMB_MAX_EDGE / Math.max(w, h);
        if (ratio >= 1) ratio = 1;                       // ảnh đã nhỏ thì giữ nguyên
        int tw = Math.max(1, (int) Math.round(w * ratio));
        int th = Math.max(1, (int) Math.round(h * ratio));

        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);                          // PNG trong suốt → nền trắng thay vì đen
        g.fillRect(0, 0, tw, th);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, tw, th, null);
        g.dispose();

        ImageIO.write(out, "jpg", thumb.toFile());
    }

    // ── EXIF orientation (tự đọc, không cần thư viện ngoài) ─────────────────
    //
    // Chỉ đọc đúng một thẻ 0x0112 (Orientation) trong segment APP1/Exif của JPEG.
    // Giá trị 1..8; 1 = đúng chiều, 3 = 180°, 6 = 90° CW, 8 = 90° CCW, v.v.
    // Lỗi/không có EXIF → trả 1 (không xoay).

    private static int readExifOrientation(Path source) {
        try (InputStream in = Files.newInputStream(source)) {
            if (in.read() != 0xFF || in.read() != 0xD8) return 1;   // không phải JPEG
            while (true) {
                int b = in.read();
                if (b == -1) return 1;
                if (b != 0xFF) continue;
                int marker;
                do { marker = in.read(); } while (marker == 0xFF);   // bỏ byte đệm 0xFF
                if (marker == -1 || marker == 0xDA || marker == 0xD9) return 1; // tới dữ liệu ảnh
                int hi = in.read(), lo = in.read();
                if (hi == -1 || lo == -1) return 1;
                int len = ((hi << 8) | lo) - 2;
                if (len < 0) return 1;
                if (marker == 0xE1) {                                // APP1 = nơi chứa Exif
                    byte[] data = in.readNBytes(len);
                    return parseExifOrientation(data);
                }
                long skipped = 0;                                    // bỏ qua segment khác
                while (skipped < len) {
                    long s = in.skip(len - skipped);
                    if (s <= 0) break;
                    skipped += s;
                }
            }
        } catch (Exception e) {
            return 1;
        }
    }

    private static int parseExifOrientation(byte[] d) {
        // "Exif\0\0" + TIFF header
        if (d.length < 14) return 1;
        if (d[0] != 'E' || d[1] != 'x' || d[2] != 'i' || d[3] != 'f' || d[4] != 0 || d[5] != 0) return 1;
        int tiff = 6;
        boolean little;
        if (d[tiff] == 'I' && d[tiff + 1] == 'I') little = true;
        else if (d[tiff] == 'M' && d[tiff + 1] == 'M') little = false;
        else return 1;

        int ifd = tiff + (int) readUInt(d, tiff + 4, 4, little);     // offset IFD0
        if (ifd + 2 > d.length) return 1;
        int count = (int) readUInt(d, ifd, 2, little);
        int entry = ifd + 2;
        for (int i = 0; i < count; i++, entry += 12) {
            if (entry + 12 > d.length) break;
            int tag = (int) readUInt(d, entry, 2, little);
            if (tag == 0x0112) {                                     // Orientation
                int val = (int) readUInt(d, entry + 8, 2, little);   // kiểu SHORT nằm ở 2 byte đầu ô value
                return (val >= 1 && val <= 8) ? val : 1;
            }
        }
        return 1;
    }

    private static long readUInt(byte[] d, int off, int len, boolean little) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            int shift = little ? (8 * i) : (8 * (len - 1 - i));
            v |= (long) (d[off + i] & 0xFF) << shift;
        }
        return v;
    }

    /** Xoay/lật ảnh về đúng chiều theo giá trị orientation (1..8). */
    private static BufferedImage applyOrientation(BufferedImage img, int o) {
        if (o <= 1) return img;
        int w = img.getWidth(), h = img.getHeight();
        boolean swap = (o >= 5);                 // 5..8 đổi chiều ngang/dọc
        int nw = swap ? h : w, nh = swap ? w : h;

        AffineTransform t;
        switch (o) {
            case 2: t = new AffineTransform(-1, 0, 0, 1, w, 0); break;   // lật ngang
            case 3: t = new AffineTransform(-1, 0, 0, -1, w, h); break;  // 180°
            case 4: t = new AffineTransform(1, 0, 0, -1, 0, h); break;   // lật dọc
            case 5: t = new AffineTransform(0, 1, 1, 0, 0, 0); break;    // transpose
            case 6: t = new AffineTransform(0, 1, -1, 0, h, 0); break;   // 90° CW
            case 7: t = new AffineTransform(0, -1, -1, 0, h, w); break;  // transverse
            case 8: t = new AffineTransform(0, -1, 1, 0, 0, w); break;   // 90° CCW
            default: return img;
        }

        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, nw, nh);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, t, null);
        g.dispose();
        return dst;
    }

    private void grabVideoFrame(Path source, Path thumb) throws Exception {
        // -ss trước -i = seek nhanh; 1 giây để tránh khung đầu đen
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
    // Helpers
    // ════════════════════════════════════════════════════════════════

    /** File HEIC/HEIF — trình duyệt (trừ Safari) không hiển thị được, phải chuyển sang JPEG */
    private static boolean isHeic(String fileName, String contentType) {
        String fn = fileName != null ? fileName.toLowerCase(Locale.ROOT) : "";
        String ct = contentType != null ? contentType.toLowerCase(Locale.ROOT) : "";
        return fn.matches(".*\\.hei[cf]$") || ct.contains("heic") || ct.contains("heif");
    }

    public static String detectMediaType(String contentType, String fileName) {
        String ct = contentType != null ? contentType.toLowerCase(Locale.ROOT) : "";
        if (ct.startsWith("video/")) return "VIDEO";
        if (ct.startsWith("image/")) return "IMAGE";

        String fn = fileName != null ? fileName.toLowerCase(Locale.ROOT) : "";
        if (fn.matches(".*\\.(mp4|mov|m4v|avi|mkv|webm)$")) return "VIDEO";
        if (fn.matches(".*\\.(jpg|jpeg|png|gif|webp|heic|heif|bmp)$")) return "IMAGE";

        throw new ToolsException("Chỉ hỗ trợ file ảnh hoặc video.");
    }

    private String extensionOf(String originalName, String contentType, String mediaType) {
        if (originalName != null && originalName.contains(".")) {
            String ext = originalName.substring(originalName.lastIndexOf('.')).toLowerCase(Locale.ROOT);
            if (ext.matches("\\.[a-z0-9]{2,5}")) return ext;
        }
        if (contentType != null) {
            if (contentType.contains("png"))  return ".png";
            if (contentType.contains("jpeg")) return ".jpg";
            if (contentType.contains("webp")) return ".webp";
            if (contentType.contains("gif"))  return ".gif";
            if (contentType.contains("quicktime")) return ".mov";
            if (contentType.contains("mp4"))  return ".mp4";
        }
        return "VIDEO".equals(mediaType) ? ".mp4" : ".jpg";
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
    }

    private static void deleteDirQuietly(Path dir) {
        if (dir == null) return;
        try (var stream = Files.list(dir)) {
            stream.forEach(MediaStorageService::deleteQuietly);
        } catch (Exception ignored) {}
        deleteQuietly(dir);
    }
}