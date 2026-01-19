package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.config.JwtService;
import com.nhatnam.server.dto.request.AuthLoginRequest;
import com.nhatnam.server.dto.request.AuthRegisterRequest;
import com.nhatnam.server.dto.response.AuthResponse;
import com.nhatnam.server.entity.Token;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.enumtype.TokenType;
import com.nhatnam.server.repository.TokenRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository;
    private final TokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Override
    @Transactional
    public void register(AuthRegisterRequest request) {
        try {
            if (userRepository.findByUsername(request.getUsername()).isPresent()) {
                throw new RuntimeException("Username already exists");
            }

            if (userRepository.findByEmail(request.getEmail()).isPresent()) {
                throw new RuntimeException("Email already exists");
            }

            User newUser = User.builder()
                    .username(request.getUsername())
                    .email(request.getEmail())
                    .fullName(request.getFullName())
                    .phoneNumber(request.getPhoneNumber())
                    .password(passwordEncoder.encode(request.getPassword()))
                    .role(Role.USER)
                    .timeCreate(System.currentTimeMillis())
                    .isLockAccount(false)
                    .build();

            userRepository.save(newUser);

            // Register không cần trả về data

        } catch (Exception e) {
            log.error("Registration failed: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage());
        }
    }

    @Override
    public AuthResponse login(AuthLoginRequest request) {
        try {
            // 1. Authenticate
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getUsername(),
                            request.getPassword()
                    )
            );

            // 2. Find user
            User user = userRepository.findByUsername(request.getUsername())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found"));

            // 3. Check account locked
            if (user.isLockAccount()) {
                throw new RuntimeException("Account is locked");
            }

            // 4. Revoke all existing tokens
            tokenRepository.findAllValidTokenByUser(user.getId())
                    .forEach(token -> {
                        token.setExpired(true);
                        token.setRevoked(true);
                        tokenRepository.save(token);
                    });

            // 5. Generate new JWT token
            String jwtToken = jwtService.generateToken(user);

            // 6. Save token
            Token token = Token.builder()
                    .user(user)
                    .token(jwtToken)
                    .tokenType(TokenType.BEARER)
                    .expired(false)
                    .revoked(false)
                    .build();
            tokenRepository.save(token);

            // 7. Build response
            return AuthResponse.builder()
                    .userId(user.getId())
                    .fullName(user.getFullName())
                    .isLock(user.isLockAccount())
                    .accessToken(jwtToken)
                    .build();

        } catch (BadCredentialsException | UsernameNotFoundException e) {
            log.warn("Login failed: Invalid credentials for user {}", request.getUsername());
            throw new RuntimeException("Account invalid");
        } catch (Exception e) {
            log.error("Login failed: {}", e.getMessage());
            throw new RuntimeException(e.getMessage());
        }
    }
}