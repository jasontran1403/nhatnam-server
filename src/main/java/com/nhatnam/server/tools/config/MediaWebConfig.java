package com.nhatnam.server.tools.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.PostConstruct;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phục vụ file trong thư viện tài nguyên tại /media/**.
 *
 * Cố ý dùng static resource handler thay vì tự stream qua controller:
 * handler của Spring hỗ trợ sẵn HTTP Range, nhờ đó video TUA ĐƯỢC và
 * trình duyệt/iOS phát được ngay mà không phải tải hết file.
 * Tự viết controller trả ResponseEntity<Resource> thì mất tính năng này.
 */
@Configuration
@RequiredArgsConstructor
@Log4j2
public class MediaWebConfig implements WebMvcConfigurer {

    @Value("${tools.media.storage-dir:./data/media}")
    private String storageDir;

    /**
     * Tạo sẵn thư mục ngay khi khởi động.
     *
     * Cần thiết vì Path.toUri() chỉ thêm dấu "/" cuối khi thư mục ĐANG TỒN TẠI.
     * Thư mục chưa có thì URI thành ".../data/media" (không có gạch chéo cuối),
     * Spring hiểu đó là một FILE chứ không phải thư mục gốc, và mọi request
     * /media/xxx.jpg đều trả 404 — dù file đã nằm sẵn trên đĩa.
     */
    @PostConstruct
    void prepareDirectory() {
        Path root = resolveRoot();
        try {
            Files.createDirectories(root);
            log.info("[Media] Thư mục tài nguyên: {}", root);
        } catch (IOException e) {
            log.error("[Media] Không tạo được thư mục {}: {}", root, e.getMessage());
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = resolveRoot().toUri().toString();
        // Bảo hiểm thêm một lần nữa: thiếu gạch chéo cuối là 404 toàn bộ
        if (!location.endsWith("/")) location += "/";

        log.info("[Media] Phục vụ /media/** từ {}", location);

        registry.addResourceHandler("/media/**")
                .addResourceLocations(location)
                .setCachePeriod(60 * 60 * 24);   // file không bao giờ đổi nội dung
    }

    private Path resolveRoot() {
        return Path.of(storageDir).toAbsolutePath().normalize();
    }
}