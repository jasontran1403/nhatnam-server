package com.nhatnam.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AppVersionUpdateRequest {

    @NotBlank(message = "platform không được để trống")
    @Pattern(regexp = "android|ios", message = "platform phải là 'android' hoặc 'ios'")
    private String platform;

    @NotBlank(message = "minVersion không được để trống")
    private String minVersion;

    @NotBlank(message = "latestVersion không được để trống")
    private String latestVersion;

    private String downloadUrl;
    private String message;
}