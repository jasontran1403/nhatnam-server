// src/main/java/com/nhatnam/server/config/AppVersionDataInitializer.java

package com.nhatnam.server.config;

import com.nhatnam.server.entity.AppVersion;
import com.nhatnam.server.repository.AppVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@Order(99)
@RequiredArgsConstructor
public class AppVersionDataInitializer implements CommandLineRunner {

    private final AppVersionRepository appVersionRepository;

    @Override
    public void run(String... args) {
        seed("android", "1.0.1", 8, "1.0.1", 8,
                "https://play.google.com/store/apps/details?id=com.your.package",
                "Phiên bản mới có nhiều cải tiến và sửa lỗi quan trọng.");

        seed("ios", "1.0.1", 8, "1.0.1", 8,
                "https://apps.apple.com/app/idXXXXXXXXX",
                "Phiên bản mới có nhiều cải tiến và sửa lỗi quan trọng.");
    }

    private void seed(String platform,
                      String minVer, int minBuild,
                      String latestVer, int latestBuild,
                      String url, String message) {
        if (appVersionRepository.findByPlatform(platform).isEmpty()) {
            appVersionRepository.save(AppVersion.builder()
                    .platform(platform)
                    .minVersion(minVer)
                    .minBuild(minBuild)
                    .latestVersion(latestVer)
                    .latestBuild(latestBuild)
                    .downloadUrl(url)
                    .message(message)
                    .build());
        }
    }
}