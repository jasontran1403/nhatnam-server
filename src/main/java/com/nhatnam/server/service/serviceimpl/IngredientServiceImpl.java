package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.dto.request.CreateIngredientRequest;
import com.nhatnam.server.dto.response.IngredientResponse;
import com.nhatnam.server.entity.Ingredient;
import com.nhatnam.server.repository.IngredientRepository;
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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class IngredientServiceImpl implements IngredientService {

    private final IngredientRepository ingredientRepository;

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

        Ingredient saved = ingredientRepository.save(ingredient);

        return mapToResponse(saved);
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

        Ingredient updated = ingredientRepository.save(ingredient);

        return mapToResponse(updated);
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
        return mapToResponse(ingredient);
    }

    @Override
    public List<IngredientResponse> getAllIngredients() {
        return ingredientRepository.findByIsActiveTrue().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<IngredientResponse> getPaginationIngredients(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        return ingredientRepository
                .findByIsActiveTrue(pageable)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private IngredientResponse mapToResponse(Ingredient ingredient) {
        return IngredientResponse.builder()
                .id(ingredient.getId())
                .name(ingredient.getName())
                .imageUrl(ingredient.getImageUrl())
                .unit(ingredient.getUnit())
                .stockQuantity(ingredient.getStockQuantity())
                .createdAt(ingredient.getCreatedAt())
                .updatedAt(ingredient.getUpdatedAt())
                .build();
    }
}