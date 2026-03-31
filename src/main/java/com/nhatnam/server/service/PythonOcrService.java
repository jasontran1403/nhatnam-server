// src/main/java/com/nhatnam/server/service/PythonOcrService.java
package com.nhatnam.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.pos.OcrInventoryItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Gửi ảnh đến Python FastAPI (port 9010) để xử lý OCR.
 * Python service chạy cùng local với Spring (localhost).
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class PythonOcrService {

    @Value("${ocr.python.url:http://localhost:9010}")
    private String pythonBaseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record OcrResult(
            String staffName,
            String date,           // "dd/MM/yyyy"
            List<OcrInventoryItem> inventoryList,
            String rawJson,
            boolean success,
            String errorMessage
    ) {}

    /**
     * Gửi ảnh bytes lên Python /ocr/inventory, nhận kết quả.
     */
    public OcrResult callOcr(byte[] imageBytes, String filename) {
        try {
            RestTemplate rest = new RestTemplate();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            ByteArrayResource resource = new ByteArrayResource(imageBytes) {
                @Override public String getFilename() { return filename; }
            };
            body.add("file", resource);

            HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = rest.postForEntity(
                    pythonBaseUrl + "/ocr/inventory", entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return new OcrResult(null, null, List.of(), null, false,
                        "Python OCR returned: " + response.getStatusCode());
            }

            String rawJson = response.getBody();
            return parseOcrResponse(rawJson);

        } catch (Exception e) {
            log.error("[OCR] Python service call failed: {}", e.getMessage());
            return new OcrResult(null, null, List.of(), null, false, e.getMessage());
        }
    }

    private OcrResult parseOcrResponse(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);

            String staffName = root.path("staffName").asText(null);
            String date      = root.path("date").asText(null);

            List<OcrInventoryItem> items = new ArrayList<>();
            JsonNode list = root.path("inventoryList");
            if (list.isArray()) {
                for (JsonNode item : list) {
                    OcrInventoryItem inv = OcrInventoryItem.builder()
                            .name(item.path("name").asText(null))
                            .packQuantity(item.has("packQuantity") && !item.path("packQuantity").isNull()
                                    ? item.path("packQuantity").asInt() : null)
                            .unitQuantity(item.has("unitQuantity") && !item.path("unitQuantity").isNull()
                                    ? item.path("unitQuantity").asDouble() : null)
                            .build();
                    items.add(inv);
                }
            }

            return new OcrResult(staffName, date, items, rawJson, true, null);

        } catch (Exception e) {
            log.error("[OCR] Parse failed: {}", e.getMessage());
            return new OcrResult(null, null, List.of(), rawJson, false, "Parse error: " + e.getMessage());
        }
    }
}