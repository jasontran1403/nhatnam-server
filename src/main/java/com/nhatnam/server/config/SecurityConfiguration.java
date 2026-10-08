package com.nhatnam.server.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static com.nhatnam.server.enumtype.Role.*;
import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfiguration {

    /**
     * QUAN TRỌNG: JwtAuthenticationFilter.isPublicPath() dùng mảng này
     * để bỏ qua kiểm tra token. Nếu path không nằm ở đây,
     * filter sẽ trả lỗi 901 trước khi request tới SecurityFilterChain.
     *
     * ── 2026-09-15: thêm /vmb-files ────────────────────────────────
     * Trước đây static resource /vmb-files/** đi qua filter chính và bị
     * verify JWT → khi FE gắn Bearer thuộc scope Tools/VMB (secret khác)
     * là fail "signature does not match". Nay cùng secret rồi và preview
     * chỉ cần UUID không đoán được (comment cũ ở VmbFileWebConfig), nên
     * đơn giản là mở public — trình duyệt gọi trực tiếp qua &lt;iframe&gt;
     * / &lt;img&gt; không cần fetch-as-blob nữa.
     */
    public static final String[] PUBLIC_API_PREFIXES = {
            "/api/auth",
            "/api/public",
            "/api/tools",
            "/ws",              // ← WebSocket STOMP endpoint
            "/media",
            "/files",
            "/vmb-files",       // ← static file mặt vé + CCCD/PP, xem VmbFileWebConfig
            "/error",
    };

    private static final String[] WHITE_LIST_URL = {
            "/api/auth/**",
            "/api/seller/products",
            "/api/seller/products/**",
            "/api/public/**",
            "/api/tools/**",
            "/ws/**",           // ← WebSocket handshake + SockJS
            "/media/**",
            "/files/**",
            "/vmb-files/**",    // ← static file mặt vé + CCCD/PP
            "/error",
            "/configuration/ui",
            "/configuration/security",
            "/webjars/**"
    };

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuthenticationProvider authenticationProvider;
    private final LogoutHandler logoutHandler;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .headers(headers -> headers.frameOptions(frame -> frame.disable()))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(req ->
                        req.requestMatchers(WHITE_LIST_URL).permitAll()

                                // ── eOffice task: user-level ──
                                .requestMatchers("/api/user/**").hasAnyRole(
                                        USER.name(), SELLER.name(), POS.name(),
                                        ACCOUNTANT.name(), SUPERADMIN.name()
                                )

                                // ── eOffice task: quản lý ──
                                .requestMatchers("/api/admin/**").hasAnyRole(
                                        ADMIN.name(), SUPERADMIN.name()
                                )

                                // einvoice: ACCOUNTANT + SUPERADMIN
                                .requestMatchers("/api/pos/einvoice/**").hasAnyRole(ACCOUNTANT.name(), SUPERADMIN.name())
                                .requestMatchers("/api/pos/**").hasRole(POS.name())
                                .requestMatchers("/api/shipper/**").hasAnyRole(SHIPPER.name(), SUPERADMIN.name())
                                .requestMatchers("/api/seller/**").hasAnyRole(SELLER.name(), SUPERADMIN.name())
                                .requestMatchers("/api/warehouser/**").hasAnyRole(WAREHOUSER.name(), SUPERADMIN.name())
                                .requestMatchers("/api/accountant/**").hasAnyRole(ACCOUNTANT.name(), SUPERADMIN.name())
                                .requestMatchers("/api/superadmin/**").hasRole(SUPERADMIN.name())
                                .anyRequest().authenticated()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .logout(logout ->
                        logout.logoutUrl("/api/auth/logout")
                                .addLogoutHandler(logoutHandler)
                                .logoutSuccessHandler((request, response, authentication) ->
                                        SecurityContextHolder.clearContext())
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}