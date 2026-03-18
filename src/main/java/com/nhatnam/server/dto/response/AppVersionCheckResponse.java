// src/main/java/com/nhatnam/server/dto/response/AppVersionCheckResponse.java

package com.nhatnam.server.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AppVersionCheckResponse {

    /** Có bản cập nhật nào không (latestVersion > currentVersion, hoặc cùng version nhưng latestBuild > currentBuild) */
    private boolean hasUpdate;

    /**
     * true  → force update: app < minVersion, hoặc cùng version nhưng build < minBuild
     * false → soft update:  app >= minVersion nhưng có bản mới hơn
     */
    private boolean updateRequired;

    /** Version mới nhất */
    private String latestVersion;

    /** Build number mới nhất (để Flutter hiển thị "build 9") */
    private int latestBuild;

    /** App Store / Play Store / APK link */
    private String downloadUrl;

    /** Thông báo hiển thị trong dialog */
    private String message;
}