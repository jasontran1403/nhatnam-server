package com.nhatnam.server.entity.pos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Chuyển đổi List<BigDecimal> ↔ JSON string để lưu vào cột TEXT.
 * VD:  [0.31, 0.29, 0.32]  ↔  "[0.31,0.29,0.32]"
 */
@Converter
public class BigDecimalListConverter
        implements AttributeConverter<List<BigDecimal>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<BigDecimal>> TYPE_REF =
            new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<BigDecimal> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Không thể chuyển List<BigDecimal> → JSON", e);
        }
    }

    @Override
    public List<BigDecimal> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, TYPE_REF);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Không thể parse JSON → List<BigDecimal>: " + json, e);
        }
    }
}