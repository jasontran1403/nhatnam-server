package com.nhatnam.server.tools.service;

import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.config.Pkcs11Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Locale;

/**
 * Nạp PrivateKey + Certificate từ USB token qua PKCS#11 cho tính năng ký PDF.
 *
 * PIN được truyền vào theo từng lần gọi và không bao giờ được lưu lại —
 * caller có trách nhiệm xóa mảng char sau khi dùng.
 *
 * Mọi lỗi cấu hình / thiết bị đều được đổi sang {@link ToolsException} kèm
 * thông báo tiếng Việt, để controller log gọn 1 dòng thay vì đổ stack trace.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class ToolsTokenService {

    /** Tên provider — đặt riêng để không đụng provider của luồng hóa đơn Viettel */
    private static final String PROVIDER_NAME = "SunPKCS11-ToolsToken";

    // Thông điệp hiển thị cho người dùng: ngắn, không lộ cấu hình máy chủ.
    // Chi tiết kỹ thuật nằm ở phần `detail` và chỉ xuất hiện trong log.
    private static final String MSG_NOT_READY =
            "Máy chủ chưa sẵn sàng để ký số. Vui lòng liên hệ quản trị hệ thống.";
    private static final String MSG_TOKEN_MISSING =
            "Không kết nối được USB token. Kiểm tra token đã cắm vào máy chủ chưa.";

    private final Pkcs11Properties props;
    private volatile Provider pkcs11Provider;

    public record TokenCredentials(PrivateKey privateKey, X509Certificate certificate) {}

    // ════════════════════════════════════════════════════════════════
    // Kiểm tra cấu hình TRƯỚC khi gọi SunPKCS11
    // ════════════════════════════════════════════════════════════════

    /**
     * SunPKCS11 báo lỗi rất khó hiểu khi cấu hình sai — ví dụ đường dẫn Windows
     * trên máy macOS/Linux thì nó ném:
     *   "Absolute path required for library value: C:/Windows/System32/..."
     * kèm nguyên stack trace, đọc mãi không ra là do sai hệ điều hành.
     * (Trên Unix, đường dẫn tuyệt đối phải bắt đầu bằng "/", nên "C:/..." bị
     * coi là đường dẫn tương đối.)
     *
     * Nên kiểm tra trước ở đây và trả thông báo nói thẳng vấn đề.
     */
    private void validateLibraryPath(String libraryPath) {
        if (libraryPath == null || libraryPath.isBlank()) {
            throw new ToolsException(MSG_NOT_READY,
                    "Chưa cấu hình tools.pkcs11.library-path trong application.yml.");
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean onWindows = os.contains("win");
        boolean looksLikeWindowsPath = libraryPath.matches("^[A-Za-z]:[/\\\\].*");

        // Đường dẫn Windows nhưng máy chủ không chạy Windows
        if (!onWindows && looksLikeWindowsPath) {
            throw new ToolsException(MSG_NOT_READY, String.format(
                    "tools.pkcs11.library-path đang trỏ tới đường dẫn Windows (%s) nhưng máy chủ chạy %s. "
                            + "Sửa sang driver PKCS#11 của hệ điều hành này, hoặc chạy backend trên chính "
                            + "máy Windows có cắm token.",
                    libraryPath, System.getProperty("os.name")));
        }

        Path path;
        try {
            path = Path.of(libraryPath);
        } catch (InvalidPathException e) {
            throw new ToolsException(MSG_NOT_READY,
                    "tools.pkcs11.library-path không hợp lệ: " + libraryPath);
        }

        if (!path.isAbsolute()) {
            throw new ToolsException(MSG_NOT_READY,
                    "tools.pkcs11.library-path phải là đường dẫn tuyệt đối, hiện là: " + libraryPath);
        }

        if (!Files.exists(path)) {
            throw new ToolsException(MSG_NOT_READY,
                    "Không tìm thấy file driver PKCS#11 tại: " + libraryPath
                            + ". Kiểm tra đã cài driver của token trên máy chủ chưa.");
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Provider
    // ════════════════════════════════════════════════════════════════

    private Provider getProvider() {
        if (pkcs11Provider != null) return pkcs11Provider;

        synchronized (this) {
            if (pkcs11Provider != null) return pkcs11Provider;

            Provider existing = Security.getProvider(PROVIDER_NAME);
            if (existing != null) {
                pkcs11Provider = existing;
                return pkcs11Provider;
            }

            String libraryPath = props.getLibraryPath();
            validateLibraryPath(libraryPath);

            Provider base = Security.getProvider("SunPKCS11");
            if (base == null) {
                throw new ToolsException(MSG_NOT_READY,
                        "JVM không có provider SunPKCS11 — bản JDK đang dùng không hỗ trợ USB token.");
            }

            String config = String.format(
                    "--%nname=ToolsToken%nlibrary=%s%nshowInfo=true%nslotListIndex=%d",
                    libraryPath, props.getSlotIndex());

            try {
                pkcs11Provider = base.configure(config);
                Security.addProvider(pkcs11Provider);
            } catch (Exception e) {
                throw new ToolsException(MSG_TOKEN_MISSING,
                        "Không khởi tạo được PKCS#11 (slot " + props.getSlotIndex() + "): " + rootMessage(e),
                        e);
            }
            log.info("[Tools] Đã khởi tạo PKCS#11 provider: {}", pkcs11Provider.getName());
        }
        return pkcs11Provider;
    }

    // ════════════════════════════════════════════════════════════════
    // Nạp khóa
    // ════════════════════════════════════════════════════════════════

    public TokenCredentials loadCredentials(char[] pin) {
        Provider provider = getProvider();

        KeyStore keyStore;
        try {
            keyStore = KeyStore.getInstance("PKCS11", provider);
            keyStore.load(null, pin);
        } catch (Exception e) {
            throw new ToolsException(describeTokenError(e), rootMessage(e), e);
        }

        try {
            String alias = resolveAlias(keyStore);
            log.info("[Tools] Ký bằng alias: {}", alias);

            PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, pin);
            X509Certificate cert  = (X509Certificate) keyStore.getCertificate(alias);

            if (privateKey == null)
                throw new ToolsException(MSG_NOT_READY, "USB token không chứa private key.");
            if (cert == null)
                throw new ToolsException(MSG_NOT_READY, "USB token không chứa certificate.");

            return new TokenCredentials(privateKey, cert);

        } catch (ToolsException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolsException(MSG_TOKEN_MISSING,
                    "Không đọc được khóa từ USB token: " + rootMessage(e), e);
        }
    }

    /**
     * Dịch mã lỗi PKCS#11 sang câu ngắn gọn cho người dùng.
     * Chi tiết kỹ thuật đi vào phần `detail` của ToolsException, chỉ hiện ở log.
     */
    private String describeTokenError(Exception e) {
        String raw = String.valueOf(rootMessage(e)).toUpperCase(Locale.ROOT);

        if (raw.contains("CKR_PIN_INCORRECT") || raw.contains("CKR_PIN_INVALID")) {
            return "Sai mã PIN của USB token.";
        }
        if (raw.contains("CKR_PIN_LOCKED")) {
            return "USB token đã bị khóa do nhập sai PIN nhiều lần.";
        }
        if (raw.contains("CKR_TOKEN_NOT_PRESENT") || raw.contains("CKR_DEVICE_REMOVED")
                || raw.contains("CKR_SLOT_ID_INVALID")) {
            return MSG_TOKEN_MISSING;
        }
        if (raw.contains("CKR_DEVICE_ERROR")) {
            return "USB token gặp lỗi. Thử rút ra cắm lại.";
        }
        return MSG_NOT_READY;
    }

    /** Tên provider để truyền cho iText khi ký */
    public String getProviderName() {
        return getProvider().getName();
    }

    private String resolveAlias(KeyStore keyStore) throws KeyStoreException {
        String configured = props.getKeyAlias();
        if (configured != null && !configured.isBlank()) return configured;

        var aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (keyStore.isKeyEntry(alias)) return alias;
        }
        throw new ToolsException(MSG_NOT_READY, "USB token không có key entry nào để ký.");
    }

    /**
     * Kiểm tra token đã cắm và PIN đúng chưa.
     * @return null nếu sẵn sàng, ngược lại là thông báo lỗi tiếng Việt
     */
    public String checkConnection(char[] pin) {
        try {
            loadCredentials(pin);
            return null;
        } catch (ToolsException e) {
            log.warn("[Tools] Kiểm tra token thất bại: {}", e.getDetail());
            return e.getMessage();
        } catch (Exception e) {
            log.warn("[Tools] Kiểm tra token thất bại: {}", rootMessage(e));
            return MSG_NOT_READY;
        }
    }

    /** @deprecated dùng {@link #checkConnection(char[])} để có thông báo cụ thể */
    @Deprecated
    public boolean testConnection(char[] pin) {
        return checkConnection(pin) == null;
    }

    /** Lấy message của nguyên nhân gốc — SunPKCS11 hay bọc lỗi thật vào tầng trong */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }
}