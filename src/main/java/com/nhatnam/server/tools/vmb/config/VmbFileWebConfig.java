package com.nhatnam.server.tools.vmb.config;

import com.nhatnam.server.tools.vmb.service.VmbStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Phục vụ file VMB qua HTTP tại /vmb-files/{storedName}.
 *
 * Đặt riêng khỏi /media/** và /files/** để không nhầm cột. Tất cả file VMB
 * đều PUBLIC (khu VMB không có auth per yêu cầu) — trình duyệt tải trực tiếp.
 * Đường dẫn "vô hại" vì {@code storedName} là UUID.ext, không đoán được.
 */
@Configuration
@RequiredArgsConstructor
public class VmbFileWebConfig implements WebMvcConfigurer {

    private final VmbStorageService storage;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = "file:" + storage.root().toString() + "/";
        registry.addResourceHandler("/vmb-files/**")
                .addResourceLocations(location);
    }
}
