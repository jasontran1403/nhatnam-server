package com.nhatnam.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.enumtype.StatusCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Log4j2
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {

        // Sai role cũng là tình huống bình thường (nhân viên vào nhầm khu vực),
        // log 1 dòng WARN kèm tài khoản + quyền hiện có, KHÔNG kèm stack trace.
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        String user  = authentication != null ? authentication.getName() : "anonymous";
        String roles = authentication != null ? String.valueOf(authentication.getAuthorities()) : "[]";
        log.warn("[Auth] Từ chối quyền — user='{}', quyền hiện có={}, request {} {}",
                user, roles, request.getMethod(), request.getRequestURI());

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");

        Map<String, Object> body = new HashMap<>();
        body.put("code", StatusCode.FORBIDDEN); // 902
        body.put("success", false);
        body.put("message", "Không có quyền truy cập tài nguyên này");

        objectMapper.writeValue(response.getWriter(), body);
    }
}