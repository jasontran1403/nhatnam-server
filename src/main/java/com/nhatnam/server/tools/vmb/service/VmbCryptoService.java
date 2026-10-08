package com.nhatnam.server.tools.vmb.service;

import com.nhatnam.server.tools.ToolsException;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mã hóa/giải mã mật khẩu của mục Tra cứu dạng ACCOUNT.
 *
 * Dùng AES-256-GCM: xác thực + bảo mật cùng lúc, chống lộ + chống sửa ciphertext.
 *
 * Khóa lấy từ {@code tools.vmb.crypto-secret} trong application.yml. Chuỗi nào
 * cũng được (dài ngắn tùy ý) — service hash SHA-256 ra 32 byte làm khóa. Đổi
 * secret = mọi mật khẩu cũ decrypt bằng khóa mới sẽ ra rác → phải re-encrypt
 * toàn bộ hoặc gõ lại.
 *
 * Định dạng ciphertext trả ra:
 *   base64URL( 12-byte-IV || ciphertext-with-tag )
 * IV mới mỗi lần encrypt → 2 lần mã hóa cùng plaintext ra chuỗi khác nhau.
 */
@Service
@Log4j2
public class VmbCryptoService {

    private static final String ALG          = "AES/GCM/NoPadding";
    private static final int    IV_BYTES     = 12;
    private static final int    TAG_BITS     = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public VmbCryptoService(@Value("${tools.vmb.crypto-secret:}") String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("[VMB][Crypto] Chưa cấu hình tools.vmb.crypto-secret — dùng khóa dự phòng, "
                    + "KHÔNG dùng cho production. Đặt chuỗi ngẫu nhiên dài vào application.yml.");
            this.key = deriveKey("vmb-fallback::nhatnam::change-me");
        } else {
            this.key = deriveKey(raw);
        }
    }

    /** Mã hóa. Trả về base64URL (không padding) — an toàn cho URL / cột TEXT. */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance(ALG);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv,  0, out, 0,          iv.length);
            System.arraycopy(enc, 0, out, iv.length,  enc.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new ToolsException("Mã hóa mật khẩu thất bại.",
                    "AES encrypt error: " + e.getMessage(), e);
        }
    }

    /** Giải mã. Ném lỗi nếu ciphertext hỏng, sai chữ ký, hoặc sai khóa. */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return "";
        try {
            byte[] all = Base64.getUrlDecoder().decode(ciphertext);
            if (all.length < IV_BYTES + 1) throw new IllegalArgumentException("payload quá ngắn");
            byte[] iv = new byte[IV_BYTES];
            byte[] enc = new byte[all.length - IV_BYTES];
            System.arraycopy(all, 0,        iv,  0, IV_BYTES);
            System.arraycopy(all, IV_BYTES, enc, 0, enc.length);
            Cipher c = Cipher.getInstance(ALG);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Không lộ chi tiết ra người dùng — có thể do đổi crypto-secret sau khi lưu password
            throw new ToolsException(
                    "Không giải mã được mật khẩu. Có thể khóa bảo mật đã đổi — nhập lại mật khẩu ở form sửa.",
                    "AES decrypt error: " + e.getMessage(), e);
        }
    }

    private static SecretKey deriveKey(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Máy chủ thiếu SHA-256", e);
        }
    }
}
