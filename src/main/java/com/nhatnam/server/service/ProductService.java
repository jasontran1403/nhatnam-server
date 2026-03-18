package com.nhatnam.server.service;

import com.nhatnam.server.dto.request.CreateCompleteProductRequest;
import com.nhatnam.server.dto.request.CreatePriceRequest;
import com.nhatnam.server.dto.request.CreateVariantRequest;
import com.nhatnam.server.dto.request.TierRequest;
import com.nhatnam.server.dto.response.ProductResponse;

import java.util.List;

public interface ProductService {
    ProductResponse deleteTier(Long productId, Long tierId);
    ProductResponse updateTier(Long productId, Long tierId, TierRequest req);
    ProductResponse addTier(Long productId, TierRequest req);

    ProductResponse createCompleteProduct(CreateCompleteProductRequest request);

    ProductResponse updateProduct(Long id, CreateCompleteProductRequest request);

    void deleteProduct(Long id);

    ProductResponse getProductById(Long id);

    List<ProductResponse> getAllProducts();

    List<ProductResponse> getProductsByCategory(String category);

    ProductResponse addVariant(CreateVariantRequest request);

    void deleteVariant(Long variantId);
}