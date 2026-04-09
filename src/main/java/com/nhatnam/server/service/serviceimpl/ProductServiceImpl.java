package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.dto.request.*;
import com.nhatnam.server.dto.response.ProductResponse;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.VatRate;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductServiceImpl implements ProductService {

    private final ProductRepository             productRepository;
    private final ProductVariantRepository      variantRepository;
    private final ProductPriceTierRepository    priceTierRepository;
    private final IngredientRepository          ingredientRepository;
    private final VariantIngredientRepository   variantIngredientRepository;
    private final ProductIngredientRepository   productIngredientRepository;
    private final CategoryRepository            categoryRepository;

    // ════════════════════════════════════════════════════════════════
    // HELPER: resolve category name từ categoryId hoặc category string
    // Flutter gửi cả 2: categoryId (int) + categoryName (String)
    // Ưu tiên categoryId nếu có
    // ════════════════════════════════════════════════════════════════
    private String resolveCategoryName(CreateCompleteProductRequest request) {
        // Ưu tiên 1: dùng categoryId để lookup tên chính xác
        if (request.getCategoryId() != null) {
            return categoryRepository.findById(request.getCategoryId())
                    .map(Category::getName)
                    .orElse(request.getCategory()); // fallback về string nếu không tìm thấy
        }
        // Ưu tiên 2: dùng string category trực tiếp
        return request.getCategory();
    }

    // ════════════════════════════════════════════════════════════════
    // HELPER: resolve categoryId từ tên (dùng khi build response)
    // ════════════════════════════════════════════════════════════════
    private Long resolveCategoryId(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) return null;
        return categoryRepository.findByName(categoryName)
                .map(Category::getId)
                .orElse(null);
    }

    // ════════════════════════════════════════════════════════════════
    // TIER CRUD
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public ProductResponse addTier(Long productId, TierRequest req) {
        long now = System.currentTimeMillis();
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));

        priceTierRepository.save(ProductPriceTier.builder()
                .product(product)
                .tierName(req.getTierName())
                .minQuantity(req.getMinQuantity())
                .maxQuantity(req.getMaxQuantity())
                .price(req.getPrice())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .isActive(true)
                .createdAt(now)
                .updatedAt(now)
                .build());

        product.setUpdatedAt(now);
        return mapToResponse(productRepository.save(product));
    }

    @Override
    @Transactional
    public ProductResponse updateTier(Long productId, Long tierId, TierRequest req) {
        long now = System.currentTimeMillis();
        ProductPriceTier tier = priceTierRepository.findByIdAndProductId(tierId, productId)
                .orElseThrow(() -> new RuntimeException("Tier not found: " + tierId));

        tier.setTierName(req.getTierName());
        tier.setMinQuantity(req.getMinQuantity());
        tier.setMaxQuantity(req.getMaxQuantity());
        tier.setPrice(req.getPrice());
        if (req.getSortOrder() != null) tier.setSortOrder(req.getSortOrder());
        tier.setUpdatedAt(now);
        priceTierRepository.save(tier);

        return mapToResponse(productRepository.findById(productId).orElseThrow());
    }

    @Override
    @Transactional
    public ProductResponse deleteTier(Long productId, Long tierId) {
        ProductPriceTier tier = priceTierRepository.findByIdAndProductId(tierId, productId)
                .orElseThrow(() -> new RuntimeException("Tier not found: " + tierId));
        priceTierRepository.delete(tier);
        return mapToResponse(productRepository.findById(productId).orElseThrow());
    }

    // ════════════════════════════════════════════════════════════════
    // PRICE (legacy backward-compat)
    // ════════════════════════════════════════════════════════════════



    // ════════════════════════════════════════════════════════════════
    // CREATE PRODUCT
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public ProductResponse createCompleteProduct(CreateCompleteProductRequest request) {
        long now = System.currentTimeMillis();

        // Resolve category: ưu tiên categoryId → lookup tên; fallback dùng string
        String categoryName = resolveCategoryName(request);

        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .imageUrl(request.getImageUrl())
                .isActive(true)
                .createdAt(now)
                .updatedAt(now)
                .category(categoryName)
                .vatRate(VatRate.fromPercentage(request.getVatRate()))
                .basePrice(request.getBasePrice() != null
                        ? request.getBasePrice() : BigDecimal.ZERO)
                .variants(new ArrayList<>())
                .priceTiers(new ArrayList<>())
                .productIngredients(new ArrayList<>())
                .build();

        product = productRepository.save(product);

        // ── Tiers ─────────────────────────────────────────────────
        if (request.getTiers() != null && !request.getTiers().isEmpty()) {
            int idx = 0;
            for (var ti : request.getTiers()) {
                priceTierRepository.save(ProductPriceTier.builder()
                        .product(product)
                        .tierName(ti.getTierName())
                        .minQuantity(ti.getMinQuantity())
                        .maxQuantity(ti.getMaxQuantity())
                        .price(ti.getPrice())
                        .sortOrder(idx++)
                        .isActive(true)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
            }
        }

        // ── Variants hoặc direct ingredients ─────────────────────
        if (request.getVariants() != null && !request.getVariants().isEmpty()) {
            for (var vi : request.getVariants()) {
                ProductVariant variant = ProductVariant.builder()
                        .product(product)
                        .variantName(vi.getVariantName())
                        .isDefault(vi.isDefault())
                        .isActive(true)
                        .createdAt(now)
                        .build();
                variant = variantRepository.save(variant);
                product.getVariants().add(variant);

                if (vi.getIngredients() != null) {
                    for (var ii : vi.getIngredients()) {
                        Ingredient ing = ingredientRepository.findById(ii.getIngredientId())
                                .orElseThrow(() -> new RuntimeException(
                                        "Ingredient not found: " + ii.getIngredientId()));
                        variantIngredientRepository.save(VariantIngredient.builder()
                                .variant(variant).ingredient(ing).build());
                    }
                }
            }
        } else if (request.getIngredients() != null && !request.getIngredients().isEmpty()) {
            for (var ii : request.getIngredients()) {
                Ingredient ing = ingredientRepository.findById(ii.getIngredientId())
                        .orElseThrow(() -> new RuntimeException(
                                "Ingredient not found: " + ii.getIngredientId()));
                ProductIngredient pi = ProductIngredient.builder()
                        .product(product).ingredient(ing).build();
                productIngredientRepository.save(pi);
                product.getProductIngredients().add(pi);
            }
        } else {
            throw new IllegalArgumentException(
                    "Phải cung cấp ít nhất biến thể hoặc nguyên liệu sản phẩm");
        }

        return mapToResponse(productRepository.findById(product.getId()).orElseThrow());
    }

    // ════════════════════════════════════════════════════════════════
    // UPDATE PRODUCT
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public ProductResponse updateProduct(Long id, CreateCompleteProductRequest request) {
        Product product = productRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));

        long now = System.currentTimeMillis();

        product.setName(request.getName());
        product.setDescription(request.getDescription());
        if (request.getImageUrl() != null) product.setImageUrl(request.getImageUrl());
        if (request.getBasePrice() != null) product.setBasePrice(request.getBasePrice());
        product.setVatRate(VatRate.fromPercentage(request.getVatRate()));
        product.setUpdatedAt(now);

        String categoryName = resolveCategoryName(request);
        if (categoryName != null && !categoryName.isBlank()) {
            product.setCategory(categoryName);
        }

        product = productRepository.save(product);

        if (request.getTiers() != null) {
            priceTierRepository.deleteByProductId(product.getId());
            priceTierRepository.flush();
            int idx = 0;
            for (var ti : request.getTiers()) {
                priceTierRepository.save(ProductPriceTier.builder()
                        .product(product)
                        .tierName(ti.getTierName())
                        .minQuantity(ti.getMinQuantity())
                        .maxQuantity(ti.getMaxQuantity())
                        .price(ti.getPrice())
                        .sortOrder(idx++)
                        .isActive(true)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
            }
        }

        List<ProductVariant> oldVariants = variantRepository.findByProductId(product.getId());

        for (ProductVariant ov : oldVariants) {
            variantIngredientRepository.deleteByVariantId(ov.getId());
        }
        variantIngredientRepository.flush();

        if (!oldVariants.isEmpty()) {
            variantRepository.deleteAllInBatch(oldVariants);
            variantRepository.flush(); // commit delete
        }

        // Tạo mới variants từ request
        if (request.getVariants() != null && !request.getVariants().isEmpty()) {
            int variantIndex = 0;
            for (var vi : request.getVariants()) {
                ProductVariant variant = variantRepository.save(ProductVariant.builder()
                        .product(product)
                        .variantName(vi.getVariantName())
                        .isDefault(vi.isDefault())
                        .isActive(true)
                        .createdAt(now)
                        .build());

                // Tạo VariantIngredient nếu request có ingredients cho variant này
                if (vi.getIngredients() != null && !vi.getIngredients().isEmpty()) {
                    for (var ii : vi.getIngredients()) {
                        Ingredient ing = ingredientRepository.findById(ii.getIngredientId())
                                .orElseThrow(() -> new RuntimeException("Ingredient not found: " + ii.getIngredientId()));
                        variantIngredientRepository.save(VariantIngredient.builder()
                                .variant(variant)
                                .ingredient(ing)
                                .build());
                    }
                }
            }
        }

        Product refreshed = productRepository.findById(product.getId()).orElseThrow();

        return mapToResponse(refreshed);
    }

    // ════════════════════════════════════════════════════════════════
    // DELETE / GET
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found"));
        product.setIsActive(false);
        product.setUpdatedAt(System.currentTimeMillis());
        productRepository.save(product);
    }

    @Override
    public ProductResponse getProductById(Long id) {
        return mapToResponse(productRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new RuntimeException("Product not found")));
    }

    @Override
    public List<ProductResponse> getAllProducts() {
        return productRepository.findByIsActiveTrue().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<ProductResponse> getProductsByCategory(String category) {
        return productRepository.findByIsActiveTrue().stream()
                .filter(p -> category.equalsIgnoreCase(p.getCategory()))
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    // ════════════════════════════════════════════════════════════════
    // VARIANT / PRICE helpers (giữ nguyên)
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public ProductResponse addVariant(CreateVariantRequest request) {
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new RuntimeException("Product not found"));
        long now = System.currentTimeMillis();

        ProductVariant variant = variantRepository.save(ProductVariant.builder()
                .product(product)
                .variantName(request.getVariantName())
                .isDefault(request.getIsDefault() != null ? request.getIsDefault() : false)
                .isActive(true)
                .createdAt(now)
                .build());

        if (request.getIngredients() != null) {
            for (var item : request.getIngredients()) {
                Ingredient ing = ingredientRepository.findById(item.getIngredientId())
                        .orElseThrow(() -> new RuntimeException("Ingredient not found"));
                variantIngredientRepository.save(VariantIngredient.builder()
                        .variant(variant).ingredient(ing).build());
            }
        }
        return mapToResponse(product);
    }


    @Override
    @Transactional
    public void deleteVariant(Long variantId) {
        ProductVariant v = variantRepository.findById(variantId)
                .orElseThrow(() -> new RuntimeException("Variant not found"));
        v.setIsActive(false);
        variantRepository.save(v);
    }


    // ════════════════════════════════════════════════════════════════
    // mapToResponse
    // ════════════════════════════════════════════════════════════════
    private ProductResponse mapToResponse(Product product) {

        // ── Tiers ─────────────────────────────────────────────────
        List<ProductResponse.PriceTierResponse> tierResponses =
                product.getPriceTiers().stream()
                        .map(t -> ProductResponse.PriceTierResponse.builder()
                                .id(t.getId())
                                .tierName(t.getTierName())
                                .minQuantity(t.getMinQuantity())
                                .maxQuantity(t.getMaxQuantity())
                                .price(t.getPrice())
                                .sortOrder(t.getSortOrder())
                                .isActive(t.getIsActive())
                                .build())
                        .collect(Collectors.toList());

        // ── Variants ──────────────────────────────────────────────
        List<ProductVariant> variants =
                variantRepository.findByProductIdAndIsActiveTrue(product.getId());
        List<ProductResponse.VariantResponse> variantResponses = variants.stream()
                .map(v -> {
                    List<ProductResponse.VariantResponse.IngredientItem> ingItems =
                            variantIngredientRepository.findByVariantId(v.getId()).stream()
                                    .map(vi -> ProductResponse.VariantResponse.IngredientItem.builder()
                                            .ingredientId(vi.getIngredient().getId())
                                            .ingredientName(vi.getIngredient().getName())
                                            .ingredientImageUrl(vi.getIngredient().getImageUrl())
                                            .unit(vi.getIngredient().getUnit())
                                            .build())
                                    .collect(Collectors.toList());
                    return ProductResponse.VariantResponse.builder()
                            .id(v.getId())
                            .variantName(v.getVariantName())
                            .isDefault(v.getIsDefault())
                            .ingredients(ingItems)
                            .build();
                })
                .collect(Collectors.toList());

        // ── Direct ingredients → gộp vào variants để Flutter đọc ─
        // Flutter đọc product.variants[0].ingredients để hiện nguyên liệu
        // Nếu không có variant nhưng có productIngredients → tạo pseudo-variant
        if (variantResponses.isEmpty()) {
            List<ProductIngredient> directIngs =
                    productIngredientRepository.findByProductId(product.getId());
            if (!directIngs.isEmpty()) {
                List<ProductResponse.VariantResponse.IngredientItem> ingItems =
                        directIngs.stream()
                                .map(pi -> ProductResponse.VariantResponse.IngredientItem.builder()
                                        .ingredientId(pi.getIngredient().getId())
                                        .ingredientName(pi.getIngredient().getName())
                                        .ingredientImageUrl(pi.getIngredient().getImageUrl())
                                        .unit(pi.getIngredient().getUnit())
                                        .build())
                                .collect(Collectors.toList());

                // Gộp vào variantResponses dưới dạng pseudo-variant
                variantResponses.add(ProductResponse.VariantResponse.builder()
                        .id(-1L)
                        .variantName("default")
                        .isDefault(true)
                        .ingredients(ingItems)
                        .build());
            }
        }

        Long categoryId = resolveCategoryId(product.getCategory());

        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .unit(product.getUnit())
                .categoryId(categoryId)
                .categoryName(product.getCategory())
                .imageUrl(product.getImageUrl())
                .isActive(product.getIsActive())
                .basePrice(product.getBasePrice())
                .vatRate(product.getVatRate() != null
                        ? product.getVatRate().getPercentage() : 0)
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .priceTiers(tierResponses)
                .variants(variantResponses)
                .build();
    }
}