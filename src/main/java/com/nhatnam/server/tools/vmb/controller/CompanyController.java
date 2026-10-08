package com.nhatnam.server.tools.vmb.controller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.vmb.dto.VmbDtos.*;
import com.nhatnam.server.tools.vmb.service.CompanyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tools/vmb/companies")
@RequiredArgsConstructor
@Log4j2
public class CompanyController {

    private final CompanyService service;

    @GetMapping
    public ResponseEntity<ApiResponse<?>> list() {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.listAll(), "OK"));
        } catch (Exception e) {
            log.error("[VMB] list companies", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không tải được danh sách công ty."));
        }
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CompanyIO>> create(@RequestBody SaveCompanyRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.create(req), "Đã tạo"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] create company", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tạo công ty thất bại."));
        }
    }

    /** Tạo chi nhánh cho 1 công ty gốc. */
    @PostMapping("/{parentId}/branches")
    public ResponseEntity<ApiResponse<CompanyIO>> createBranch(
            @PathVariable Long parentId, @RequestBody SaveCompanyRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.createBranch(parentId, req), "Đã tạo chi nhánh"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] create branch of {}", parentId, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Tạo chi nhánh thất bại."));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CompanyIO>> update(@PathVariable Long id, @RequestBody SaveCompanyRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(service.update(id, req), "Đã cập nhật"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] update company {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Cập nhật thất bại."));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (ToolsException e) {
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[VMB] delete company {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(500, "Xóa thất bại."));
        }
    }
}
