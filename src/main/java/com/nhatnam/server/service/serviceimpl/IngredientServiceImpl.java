package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.dto.request.CreateIngredientRequest;
import com.nhatnam.server.dto.response.IngredientResponse;
import com.nhatnam.server.entity.Ingredient;
import com.nhatnam.server.entity.SellerWarehouse;
import com.nhatnam.server.entity.WarehouseIngredientStock;
import com.nhatnam.server.repository.IngredientRepository;
import com.nhatnam.server.repository.SellerWarehouseRepository;
import com.nhatnam.server.repository.WarehouseIngredientStockRepository;
import com.nhatnam.server.service.IngredientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class IngredientServiceImpl implements IngredientService {

    private final IngredientRepository               ingredientRepository;
    private final SellerWarehouseRepository          sellerWarehouseRepository;
    private final WarehouseIngredientStockRepository warehouseStockRepository;

    @Override
    @Transactional
    public IngredientResponse createIngredient(CreateIngredientRequest request) {
        long now = System.currentTimeMillis();
        Ingredient ingredient = Ingredient.builder()
                .name(request.getName())
                .imageUrl(request.getImageUrl())
                .unit(request.getUnit())
                .stockQuantity(request.getStockQuantity())
                .isActive(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return mapToResponse(ingredientRepository.save(ingredient), null);
    }

    @Override
    @Transactional
    public IngredientResponse updateIngredient(Long id, CreateIngredientRequest request) {
        Ingredient ingredient = ingredientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ingredient not found with ID: " + id));
        ingredient.setName(request.getName());
        ingredient.setImageUrl(request.getImageUrl());
        ingredient.setUnit(request.getUnit());
        ingredient.setStockQuantity(request.getStockQuantity());
        ingredient.setUpdatedAt(System.currentTimeMillis());
        return mapToResponse(ingredientRepository.save(ingredient), null);
    }

    @Override
    @Transactional
    public void deleteIngredient(Long id) {
        Ingredient ingredient = ingredientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ingredient not found with ID: " + id));
        ingredient.setIsActive(false);
        ingredient.setUpdatedAt(System.currentTimeMillis());
        ingredientRepository.save(ingredient);
    }

    @Override
    public IngredientResponse getIngredientById(Long id) {
        Ingredient ingredient = ingredientRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new RuntimeException("Ingredient not found with ID: " + id));
        return mapToResponse(ingredient, null);
    }

    @Override
    public List<IngredientResponse> getAllIngredients() {
        return ingredientRepository.findByIsActiveTrue().stream()
                .map(i -> mapToResponse(i, null))
                .collect(Collectors.toList());
    }

    @Override
    public List<IngredientResponse> getPaginationIngredients(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        return ingredientRepository.findByIsActiveTrue(pageable).stream()
                .map(i -> mapToResponse(i, null))
                .collect(Collectors.toList());
    }

    /**
     * Lấy danh sách ingredient với stockQuantity đúng theo kho của seller.
     *
     * - Seller có SellerWarehouse mapping (kho mới) → stockQuantity từ WarehouseIngredientStock
     *   (nếu chưa có record trong bảng đó → trả 0)
     * - Seller không có mapping (kho cũ) → stockQuantity từ ingredient.stockQuantity
     */
    public List<IngredientResponse> getAllIngredientsForSeller(Long sellerId) {
        List<Ingredient> ingredients = ingredientRepository.findByIsActiveTrue();

        Optional<SellerWarehouse> sw = sellerWarehouseRepository.findBySellerId(sellerId);

        if (sw.isEmpty()) {
            // Legacy seller → dùng ingredient.stockQuantity
            return ingredients.stream()
                    .map(i -> mapToResponse(i, null))
                    .collect(Collectors.toList());
        }

        // Kho mới → load WarehouseIngredientStock, build Map<ingredientId, stockQuantity>
        Long warehouseId = sw.get().getWarehouse().getId();
        List<WarehouseIngredientStock> stocks = warehouseStockRepository.findByWarehouseId(warehouseId);
        Map<Long, BigDecimal> stockMap = stocks.stream()
                .collect(Collectors.toMap(
                        s -> s.getIngredient().getId(),
                        WarehouseIngredientStock::getStockQuantity));

        return ingredients.stream()
                .map(i -> mapToResponse(i, stockMap.getOrDefault(i.getId(), BigDecimal.ZERO)))
                .collect(Collectors.toList());
    }

    public List<IngredientResponse> getPaginationIngredientsForSeller(Long sellerId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        List<Ingredient> ingredients = ingredientRepository.findByIsActiveTrue(pageable);

        Optional<SellerWarehouse> sw = sellerWarehouseRepository.findBySellerId(sellerId);
        if (sw.isEmpty()) {
            return ingredients.stream().map(i -> mapToResponse(i, null)).collect(Collectors.toList());
        }

        Long warehouseId = sw.get().getWarehouse().getId();
        List<WarehouseIngredientStock> stocks = warehouseStockRepository.findByWarehouseId(warehouseId);
        Map<Long, BigDecimal> stockMap = stocks.stream()
                .collect(Collectors.toMap(
                        s -> s.getIngredient().getId(),
                        WarehouseIngredientStock::getStockQuantity));

        return ingredients.stream()
                .map(i -> mapToResponse(i, stockMap.getOrDefault(i.getId(), BigDecimal.ZERO)))
                .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * @param overrideStock null = dùng ingredient.stockQuantity (kho cũ)
     *                      non-null = dùng giá trị này (kho mới)
     */
    private IngredientResponse mapToResponse(Ingredient ingredient, BigDecimal overrideStock) {
        return IngredientResponse.builder()
                .id(ingredient.getId())
                .name(ingredient.getName())
                .imageUrl(ingredient.getImageUrl())
                .unit(ingredient.getUnit())
                .stockQuantity(overrideStock != null ? overrideStock : ingredient.getStockQuantity())
                .costPrice(ingredient.getCostPrice())
                .createdAt(ingredient.getCreatedAt())
                .updatedAt(ingredient.getUpdatedAt())
                .build();
    }
}