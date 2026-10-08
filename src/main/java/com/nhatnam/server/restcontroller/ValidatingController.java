package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.enumtype.StatusCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Log4j2
@RequestMapping("/api/validating")
public class ValidatingController {

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        // Chỉ xảy ra nếu /api/auth/me bị cấu hình permitAll (chưa bảo vệ đúng).
        // Trả BAD_REQUEST (không phải 923) để client coi như "không xác định"
        // và fallback về cơ chế reactive thay vì đá nhầm user.
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.BAD_REQUEST, "Not authenticated"));
        }

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("username", authentication.getName());
        data.put("authorities", authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList());

        return ResponseEntity.ok(
                ApiResponse.success(StatusCode.SUCCESS, data, "OK"));
    }
}
