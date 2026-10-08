package com.nhatnam.server.tools.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cấu hình USB token dùng cho tính năng KÝ SỐ PDF (/api/tools/sign).
 *
 * Lưu ý: đây là cấu hình RIÊNG, không dùng chung với phần ký hóa đơn Viettel
 * (viettel.einvoice.pkcs11-*). Hai luồng có thể dùng chung một token vật lý
 * nhưng tách cấu hình để đổi token cho từng nghiệp vụ mà không ảnh hưởng nhau.
 *
 * application.yml:
 *   tools:
 *     pkcs11:
 *       library-path: "C:/Windows/System32/winca_csp11_v1.dll"
 *       slot-index: 0
 *       key-alias: ""
 *
 * QUAN TRỌNG — library-path phụ thuộc HỆ ĐIỀU HÀNH CỦA MÁY CHỦ, không phải máy
 * người dùng. Đường dẫn Windows đặt trên server macOS/Linux sẽ lỗi ngay khi ký.
 *   Windows: C:/Windows/System32/winca_csp11_v1.dll
 *   macOS  : /Library/Frameworks/eToken.framework/Versions/A/libeToken.dylib
 *   Linux  : /usr/lib/libeToken.so   (tùy hãng token)
 *
 * USB token cũng phải cắm vào chính MÁY CHỦ chạy backend — cắm ở máy cá nhân
 * thì server không đọc được.
 */
@Data
@Component
@ConfigurationProperties(prefix = "tools.pkcs11")
public class Pkcs11Properties {
    private String libraryPath;
    private int    slotIndex = 0;
    private String keyAlias  = "";   // rỗng → lấy key entry đầu tiên

    /**
     * Ảnh dấu/chữ ký vẽ đè lên ô chữ ký (giống Foxit/Adobe).
     * Rỗng → tự tìm ở classpath theo thứ tự: /sign/stamp.png rồi /watermarks/logo.png.
     * Đặt "none" để tắt hẳn, chỉ hiện chữ.
     */
    private String signLogoPath = "";

    /** Độ mờ của ảnh dấu, 0–1. Quá đậm sẽ che mất chữ bên dưới. */
    private double signLogoOpacity = 0.15;

    /**
     * CHIỀU CAO ảnh dấu so với chiều cao ô chữ ký.
     * 1.0 = cao bằng đúng khối chữ. Cho phép tới 3.0 nếu muốn tràn ra ngoài.
     * Bề rộng tự suy theo tỉ lệ gốc của ảnh; vượt quá bề rộng ô ký thì tự thu nhỏ.
     */
    private double signLogoScale = 1.0;

    /**
     * Dịch ảnh dấu lên trên bao nhiêu DÒNG chữ (số âm = dịch xuống).
     * Một dòng = 1/6 chiều cao ô ký.
     *
     * Ảnh luôn được giữ nằm trọn trong ô ký, nên nếu sign-logo-scale = 1.0
     * (ảnh cao bằng ô ký) thì không còn chỗ để dịch — hạ scale xuống khoảng
     * 0.7 mới thấy tác dụng.
     */
    private double signLogoOffsetLines = 3.0;

    /** Font tiếng Việt cho chữ ký. Rỗng → dùng font mặc định (mất dấu). */
    private String fontRegularPath = "C:/Windows/Fonts/arial.ttf";
}