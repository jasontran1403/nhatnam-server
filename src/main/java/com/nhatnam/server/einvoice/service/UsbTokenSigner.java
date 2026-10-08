package com.nhatnam.server.einvoice.service;

import com.nhatnam.server.einvoice.ViettelEInvoiceConfig;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Enumeration;

/**
 * Ký số bằng USB token WinCA qua PKCS#11.
 *
 * Luồng:
 *  1. Load PKCS#11 provider từ winca_csp11_v1.dll
 *  2. Mở KeyStore bằng PIN
 *  3. Tìm PrivateKey trong slot
 *  4. Ký hashData (SHA256withRSA) → trả về Base64 signature
 */
@Component
@Log4j2
public class UsbTokenSigner {

    private final ViettelEInvoiceConfig config;

    public UsbTokenSigner(ViettelEInvoiceConfig config) {
        this.config = config;
    }

    /**
     * Ký chuỗi hashData nhận từ Viettel bằng USB token WinCA.
     *
     * @param hashData chuỗi hash Viettel trả về (plain string, không phải hex)
     * @return chữ ký Base64
     */
    public String sign(String hashData) {
        try {
            log.info("[UsbToken] Bắt đầu ký, libraryPath={}", config.getPkcs11LibraryPath());

            // 1. Tạo PKCS#11 config string
            String pkcs11Config = buildPkcs11Config();
            log.debug("[UsbToken] PKCS#11 config:\n{}", pkcs11Config);

            // 2. Load SunPKCS11 provider
            Provider provider = loadPkcs11Provider(pkcs11Config);
            Security.addProvider(provider);

            // 3. Mở KeyStore PKCS#11 bằng PIN
            KeyStore ks = KeyStore.getInstance("PKCS11", provider);
            char[] pin = config.getPkcs11Pin().toCharArray();
            ks.load(null, pin);
            log.info("[UsbToken] KeyStore loaded, aliases count={}", countAliases(ks));

            // 4. Tìm PrivateKey
            PrivateKey privateKey = findPrivateKey(ks, pin);
            if (privateKey == null) {
                throw new RuntimeException("Không tìm thấy PrivateKey trong USB token");
            }

            // 5. Ký hashData
            // Viettel trả hashData là chuỗi cần ký trực tiếp bằng SHA256withRSA
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(hashData.getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = sig.sign();

            String result = Base64.getEncoder().encodeToString(signatureBytes);
            log.info("[UsbToken] Ký thành công, signature length={}", result.length());
            return result;

        } catch (Exception e) {
            log.error("[UsbToken] Lỗi ký: {}", e.getMessage(), e);
            throw new RuntimeException("Lỗi ký USB token: " + e.getMessage(), e);
        }
    }

    /**
     * Lấy certificateSerial từ USB token (để gửi lên Viettel ở bước GetHash).
     * Dùng khi không config sẵn trong application.yml.
     */
    public String getCertificateSerial() {
        if (config.getCertificateSerial() != null && !config.getCertificateSerial().isBlank()) {
            return config.getCertificateSerial();
        }
        try {
            String pkcs11Config = buildPkcs11Config();
            Provider provider = loadPkcs11Provider(pkcs11Config);
            Security.addProvider(provider);

            KeyStore ks = KeyStore.getInstance("PKCS11", provider);
            ks.load(null, config.getPkcs11Pin().toCharArray());

            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (ks.isCertificateEntry(alias) || ks.isKeyEntry(alias)) {
                    X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
                    if (cert != null) {
                        String serial = cert.getSerialNumber().toString(16);
                        log.info("[UsbToken] Found cert serial={}", serial);
                        return serial;
                    }
                }
            }
            throw new RuntimeException("Không tìm thấy certificate trong USB token");
        } catch (Exception e) {
            throw new RuntimeException("Lỗi đọc certificateSerial: " + e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────

    private String buildPkcs11Config() {
        return String.format(
                "name = WinCA\n" +
                        "library = %s\n" +
                        "slot = %d\n",
                config.getPkcs11LibraryPath().replace("\\", "/"),
                config.getPkcs11SlotIndex()
        );
    }

    private Provider loadPkcs11Provider(String configString) throws Exception {
        // Java 9+: SunPKCS11 dùng inline config ("--" prefix)
        // Không import trực tiếp sun.security.pkcs11 để tránh lỗi module
        Provider p = Security.getProvider("SunPKCS11");
        if (p != null) {
            // Provider đã có sẵn — configure lại với inline config
            return p.configure("--" + configString);
        }
        // Chưa có provider → load qua reflection
        Class<?> cls = Class.forName("sun.security.pkcs11.SunPKCS11");
        p = (Provider) cls.getDeclaredConstructor().newInstance();
        return p.configure("--" + configString);
    }

    private PrivateKey findPrivateKey(KeyStore ks, char[] pin) throws Exception {
        // Ưu tiên alias được config sẵn
        String configAlias = config.getPkcs11LibraryPath(); // dùng tạm
        // thực ra lấy từ config.getKeyAlias() nếu có

        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            log.debug("[UsbToken] Found alias: {}", alias);
            if (ks.isKeyEntry(alias)) {
                Key key = ks.getKey(alias, pin);
                if (key instanceof PrivateKey) {
                    log.info("[UsbToken] Using key alias={}", alias);
                    return (PrivateKey) key;
                }
            }
        }
        return null;
    }

    private int countAliases(KeyStore ks) {
        try {
            int count = 0;
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) { aliases.nextElement(); count++; }
            return count;
        } catch (Exception e) { return -1; }
    }
}