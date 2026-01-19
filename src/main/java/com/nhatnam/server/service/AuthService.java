package com.nhatnam.server.service;

import com.nhatnam.server.dto.request.AuthLoginRequest;
import com.nhatnam.server.dto.request.AuthRegisterRequest;
import com.nhatnam.server.dto.response.AuthResponse;

public interface AuthService {
    void register(AuthRegisterRequest request) throws Exception;
    AuthResponse login(AuthLoginRequest request) throws Exception;
}