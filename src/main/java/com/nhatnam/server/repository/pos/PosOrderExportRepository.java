// src/main/java/com/nhatnam/server/repository/pos/PosOrderExportRepository.java
package com.nhatnam.server.repository.pos;

import com.nhatnam.server.dto.PosOrderExportDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;


@Repository
@Log4j2
public class PosOrderExportRepository {

    @PersistenceContext
    private EntityManager em;

    private static final String SELECT = """
    SELECT
        st.id, st.name, st.address, st.phone,
        sh.id, sh.staffName, sh.openTime, sh.closeTime,
        o.id, o.orderCode, o.customerName, o.customerPhone,
        o.totalAmount, o.finalAmount,
        o.orderSource, o.paymentMethod, o.createdAt,
        i.categoryName, i.productName,
        i.basePrice, i.finalUnitPrice, i.discountPercent, i.quantity, i.vatAmount,
        i.id,
        ing.ingredientName, ing.quantityUsed, ing.selectedCount, ing.unitWeights
    FROM PosOrder o
    JOIN o.shift sh
    JOIN o.store st
    LEFT JOIN o.items i
    LEFT JOIN i.selectedIngredients ing
    """;

    public List<PosOrderExportDto> findForStore(Long storeId,
                                                Long fromMs, Long toMs) {
        List<Object[]> raw = em.createQuery(SELECT + """
                WHERE st.id = :storeId
                  AND o.createdAt BETWEEN :fromMs AND :toMs
                  AND o.status = 'COMPLETED'
                ORDER BY sh.openTime ASC, o.createdAt ASC, i.id ASC NULLS LAST, ing.id ASC NULLS LAST
                """, Object[].class)
                .setParameter("storeId", storeId)
                .setParameter("fromMs",  fromMs)
                .setParameter("toMs",    toMs)
                .getResultList();
        return raw.stream().map(PosOrderExportRepository::map).toList();
    }

    public List<PosOrderExportDto> findForAllStores(Long fromMs, Long toMs) {
        List<Object[]> raw = em.createQuery(SELECT + """
                WHERE o.createdAt BETWEEN :fromMs AND :toMs
                  AND o.status = 'COMPLETED'
                ORDER BY st.id ASC, sh.openTime ASC, o.createdAt ASC, i.id ASC NULLS LAST, ing.id ASC NULLS LAST
                """, Object[].class)
                .setParameter("fromMs", fromMs)
                .setParameter("toMs",   toMs)
                .getResultList();
        return raw.stream().map(PosOrderExportRepository::map).toList();
    }

    // Map Object[] → DTO (index phải khớp thứ tự SELECT)
    private static PosOrderExportDto map(Object[] r) {
        int i = 0;
        return new PosOrderExportDto(
                toLong(r[i++]),
                str(r[i++]),
                str(r[i++]),
                str(r[i++]),
                toLong(r[i++]),
                str(r[i++]),
                toLong(r[i++]),
                toLong(r[i++]),
                toLong(r[i++]),
                str(r[i++]),
                str(r[i++]),
                str(r[i++]),
                toBD(r[i++]),
                toBD(r[i++]),
                r[i++] != null ? r[i-1].toString() : null,
                str(r[i++]),
                toLong(r[i++]),
                str(r[i++]),
                str(r[i++]),
                toBD(r[i++]),
                toBD(r[i++]),
                toBD(r[i++]),
                toInt(r[i++]),
                toBD(r[i++]),
                toLong(r[i++]),   // orderItemId
                str(r[i++]),                  // ingredientName
                toBD(r[i++]),                 // ingredientQty
                toInt(r[i++]),                // ingredientSelectedCount
                toUnitWeightsStr(r[i])        // ingredientUnitWeights ← dùng helper mới
        );
    }

    // Helper chuyển List<BigDecimal> hoặc String → String JSON
    private static String toUnitWeightsStr(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        if (o instanceof List<?> list) {
            // JPA đã convert thành List<BigDecimal>
            if (list.isEmpty()) return null;
            String joined = list.stream()
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.joining(","));
            return "[" + joined + "]";
        }
        return o.toString();
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Long l) return l;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    private static Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Integer n) return n;
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(o.toString());
    }

    private static BigDecimal toBD(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        return new BigDecimal(o.toString());
    }
}