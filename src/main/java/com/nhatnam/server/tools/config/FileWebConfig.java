package com.nhatnam.server.tools.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phục vụ kho Tệp tại /files/**.
 *
 * Giống MediaWebConfig: dùng static resource handler để có sẵn HTTP Range —
 * cần cho tua video và cho pdf.js chỉ tải trang đang xem thay vì cả file 50 MB.
 *
 * Thư mục phải được tạo NGAY khi khởi động: Path.toUri() chỉ thêm "/" ở cuối
 * khi thư mục đã tồn tại, thiếu dấu đó thì Spring hiểu là một file và trả 404
 * cho mọi request /files/xxx.
 */
@Configuration
@RequiredArgsConstructor
@Log4j2
public class FileWebConfig implements WebMvcConfigurer {

    @Value("${tools.files.storage-dir:./data/files}")
    private String storageDir;

    @PostConstruct
    void prepareDirectory() {
        Path root = resolveRoot();
        try {
            Files.createDirectories(root);
            log.info("[Files] Thư mục kho tệp: {}", root);
        } catch (IOException e) {
            log.error("[Files] Không tạo được thư mục {}: {}", root, e.getMessage());
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = resolveRoot().toUri().toString();
        if (!location.endsWith("/")) location += "/";

        log.info("[Files] Phục vụ /files/** từ {}", location);

        registry.addResourceHandler("/files/**")
                .addResourceLocations(location)
                // Nội dung CÓ THỂ đổi (sửa bảng tính rồi lưu đè) nên cache ngắn,
                // khác /media/** vốn bất biến nên cache 1 ngày.
                .setCachePeriod(60);
    }

    private Path resolveRoot() {
        return Path.of(storageDir).toAbsolutePath().normalize();
    }
}
