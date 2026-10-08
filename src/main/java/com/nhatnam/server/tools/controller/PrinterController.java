package com.nhatnam.server.tools.controller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.tools.ToolsException;
import com.nhatnam.server.tools.service.PrinterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Máy in — base path /api/tools/printers */
@RestController
@RequestMapping("/api/tools/printers")
@RequiredArgsConstructor
@Log4j2
public class PrinterController {

    private final PrinterService printers;

    /** GET /api/tools/printers — máy in đã cài trên máy chủ */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list() {
        try {
            return ResponseEntity.ok(ApiResponse.success(printers.installed(), "OK"));
        } catch (Exception e) {
            log.error("[Print] list error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Không đọc được danh sách máy in."));
        }
    }

    /**
     * POST /api/tools/printers/discover — quét mạng.
     * Mất vài giây nên frontend phải hiện trạng thái đang quét, đừng để nút
     * chết lặng làm người dùng bấm lại nhiều lần.
     */
    @PostMapping("/discover")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> discover() {
        try {
            var found = printers.discover();
            return ResponseEntity.ok(ApiResponse.success(found,
                    found.isEmpty() ? "Không tìm thấy máy in nào trong mạng" : "Tìm thấy " + found.size() + " máy in"));
        } catch (Exception e) {
            log.error("[Print] discover error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Quét mạng thất bại."));
        }
    }

    /** POST /api/tools/printers/connect — { uri, name } */
    @PostMapping("/connect")
    public ResponseEntity<ApiResponse<Map<String, Object>>> connect(@RequestBody Map<String, String> body) {
        try {
            String name = printers.connect(body.get("uri"), body.get("name"));
            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("name", name), "Đã kết nối máy in " + name));
        } catch (ToolsException e) {
            log.warn("[Print] connect: {}", e.getDetail());
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Print] connect error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Kết nối máy in thất bại."));
        }
    }

    /** POST /api/tools/printers/print — { fileId, printer, options{...} } */
    @SuppressWarnings("unchecked")
    @PostMapping("/print")
    public ResponseEntity<ApiResponse<Map<String, Object>>> print(@RequestBody Map<String, Object> body) {
        try {
            Long fileId = Long.valueOf(String.valueOf(body.get("fileId")));
            String printer = (String) body.get("printer");
            Map<String, Object> options = body.get("options") instanceof Map
                    ? (Map<String, Object>) body.get("options")
                    : Map.of();

            String jobId = printers.print(fileId, printer, options);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", jobId);
            return ResponseEntity.ok(ApiResponse.success(m, "Đã gửi lệnh in"));

        } catch (ToolsException e) {
            log.warn("[Print] print: {}", e.getDetail());
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            log.error("[Print] print error", e);
            return ResponseEntity.ok(ApiResponse.error(500, "Gửi lệnh in thất bại."));
        }
    }
}
