package com.nhatnam.server.tools.vmb.service;

import com.nhatnam.server.tools.ToolsException;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;
import java.util.UUID;

/**
 * Kho file dùng chung cho khu Vé máy bay:
 *   - Mặt vé (TicketFile)
 *   - Hóa đơn nháp/phát hành/điều chỉnh + biên bản (Invoice)
 *   - Ảnh/PDF CCCD/Hộ chiếu (Passenger)
 *
 * Thư mục cấu hình bởi {@code tools.vmb.storage-dir} (mặc định ./data/vmb).
 * File được phục vụ qua static resource handler ở /vmb-files/** — cấu hình bằng
 * {@link com.nhatnam.server.tools.vmb.config.VmbFileWebConfig}.
 *
 * ── Vì sao không tái dùng MediaStorageService / FileStorageService? ──────
 * Hai kho kia:
 *   - Có {@code owner}, sinh thumbnail, có album, có luồng chunked upload,
 *     có convert HEIC → JPEG.
 *   - Ràng buộc tên hiển thị trùng nhau (sinh hậu tố (1), (2)...).
 * Ở đây file bám theo bản ghi cha (ticket / invoice / passenger), không cần
 * unique tên, không cần thumbnail, không cần chunked (kích cỡ hóa đơn/mặt vé
 * không lớn). Dùng lại sẽ phải tắt/bypass 60% logic không cần thiết.
 */
@Service
@Log4j2
public class VmbStorageService {

    private static final long MAX_SIZE = 30L * 1024 * 1024;  // 30 MB / file — dư cho PDF hoặc ảnh giấy tờ

    private final Path root;

    public VmbStorageService(@Value("${tools.vmb.storage-dir:./data/vmb}") String storageDir) {
        this.root = Path.of(storageDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            // Ném IllegalState để Spring không khởi tạo được bean → dừng khởi động,
            // tốt hơn là chạy được nhưng mọi upload đều fail bí ẩn.
            throw new IllegalStateException("Không tạo được thư mục lưu file VMB: " + root, e);
        }
        log.info("[VMB] Thư mục lưu file: {}", root);
    }

    public Path root() { return root; }

    /**
     * Lưu file MultipartFile vào đĩa và trả về {@code (storedName, originalName)}.
     * Caller tự ghi vào cột phù hợp của entity (draftFile / issuedFile / ...).
     *
     * Không cho file quá 30 MB — hóa đơn / mặt vé / ảnh giấy tờ chuẩn thường < 5 MB.
     * Nếu vượt, người dùng thấy thông báo rõ, khỏi phải chờ đứt request.
     */
    public Stored store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ToolsException("Chưa chọn file để tải lên.");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new ToolsException("File quá lớn (tối đa 30 MB).");
        }

        String ext = extensionOf(file.getOriginalFilename());
        String stored = UUID.randomUUID() + ext;
        Path target = root.resolve(stored);

        try {
            file.transferTo(target.toFile());
        } catch (IOException e) {
            throw new ToolsException("Lưu file thất bại.",
                    "Không ghi được " + target + ": " + e.getMessage(), e);
        }
        return new Stored(stored, file.getOriginalFilename(), file.getContentType(), file.getSize());
    }

    /**
     * Xóa file trên đĩa nếu còn (không thất bại khi file thiếu — vì entity có
     * thể đã bị xóa nửa chừng, hoặc file đã bị dọn tay).
     */
    public void delete(String storedName) {
        if (storedName == null || storedName.isBlank()) return;
        try {
            Files.deleteIfExists(root.resolve(storedName));
        } catch (IOException e) {
            log.warn("[VMB] Xóa file thất bại {}: {}", storedName, e.getMessage());
        }
    }

    public Path resolve(String storedName) {
        if (storedName == null || storedName.isBlank()) return null;
        // Chống path traversal — chỉ chấp nhận tên gọn "uuid.ext"
        if (storedName.contains("/") || storedName.contains("\\") || storedName.contains("..")) {
            throw new ToolsException("Tên file không hợp lệ.");
        }
        return root.resolve(storedName);
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot >= name.length() - 1) return "";
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ext.matches("[a-z0-9]{1,10}")) return "";
        return "." + ext;
    }

    public record Stored(String storedName, String originalName, String contentType, long size) {}
}
