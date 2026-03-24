package com.nhatnam.server.restcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.config.TransactionLockManager;
import com.nhatnam.server.dto.InvoiceDTO;
import com.nhatnam.server.dto.request.*;
import com.nhatnam.server.dto.response.*;
import com.nhatnam.server.entity.InventoryLog;
import com.nhatnam.server.enumtype.InventoryAction;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.exception.PriceChangedException;
import com.nhatnam.server.repository.InventoryLogRepository;
import com.nhatnam.server.service.*;
import com.nhatnam.server.utils.InvoicePdf;
import com.nhatnam.server.utils.TelegramService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@Log4j2
@RequestMapping("/api/seller")
public class SellerController {
    private final InvoicePdf invoicePdf;
    private final TransactionLockManager transactionLockManager;
    private final ProductService productService;
    private final OrderService orderService;
    private final IngredientService ingredientService;
    private final FileStorageService fileStorageService;
    private final CategoryService categoryService;
    private final CustomerService customerService;
    private final InventoryLogRepository inventoryLogRepository;
    private final ManualImportService manualImportService;
    private final ObjectMapper objectMapper;

    @PostMapping(
            value    = "/inventory-imports/manual",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ApiResponse<ManualImportResponse>> manualImport(
            @RequestPart("data")                             String        dataJson,
            @RequestPart(value = "image", required = false)  MultipartFile receiptImage,
            Authentication authentication) {

        if (authentication == null || !authentication.isAuthenticated())
            return ResponseEntity.ok(ApiResponse.error(StatusCode.UNAUTHORIZED, "Unauthorized"));

        try {
            User actor = (User) authentication.getPrincipal();

            // Parse JSON part
            ManualImportRequest request = objectMapper.readValue(dataJson, ManualImportRequest.class);

            // Upload ảnh phiếu nếu có → lưu vào seller-import/
            if (receiptImage != null && !receiptImage.isEmpty()) {
                String imageUrl = fileStorageService.saveSellerImportReceiptImage(receiptImage);
                request.setReceiptImageUrl(imageUrl);
                log.info("[SELLER] Receipt image saved: {}", imageUrl);
            }

            ManualImportResponse result = manualImportService.importBatch(request, actor);

            log.info("[SELLER] Manual import batch {} — {} items by {} | supplier={} | image={}",
                    result.getBatchCode(), result.getTotalItems(), actor.getUsername(),
                    result.getSupplierRef()    != null ? result.getSupplierRef()    : "-",
                    result.getReceiptImageUrl() != null ? result.getReceiptImageUrl() : "-");

            return ResponseEntity.ok(
                    ApiResponse.success(result, "Nhập kho thành công. Batch: " + result.getBatchCode())
            );
        } catch (RuntimeException e) {
            log.error("[SELLER] Manual import failed: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[SELLER] Manual import unexpected error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }


    @PostMapping("/products/{id}/tiers")
    public ResponseEntity<ApiResponse<ProductResponse>> addTier(
            @PathVariable Long id,
            @Valid @RequestBody TierRequest req,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                productService.addTier(id, req), "Tier added"));
    }

    // PUT /api/seller/products/{id}/tiers/{tierId}
    @PutMapping("/products/{id}/tiers/{tierId}")
    public ResponseEntity<ApiResponse<ProductResponse>> updateTier(
            @PathVariable Long id,
            @PathVariable Long tierId,
            @Valid @RequestBody TierRequest req,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                productService.updateTier(id, tierId, req), "Tier updated"));
    }

    // DELETE /api/seller/products/{id}/tiers/{tierId}
    @DeleteMapping("/products/{id}/tiers/{tierId}")
    public ResponseEntity<ApiResponse<ProductResponse>> deleteTier(
            @PathVariable Long id,
            @PathVariable Long tierId,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                productService.deleteTier(id, tierId), "Tier deleted"));
    }

    @GetMapping("/inventory-logs")
    public ResponseEntity<ApiResponse<Page<InventoryLogResponse>>> getInventoryLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "2000") int size,
            @RequestParam(required = false) Long ingredientId) {

        try {
            Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

            Page<InventoryLog> logsPage;
            if (ingredientId != null) {
                logsPage = inventoryLogRepository.findByIngredientIdOrderByCreatedAtDesc(ingredientId, pageable);
            } else {
                logsPage = inventoryLogRepository.findAll(pageable);
            }

            Page<InventoryLogResponse> responsePage = logsPage.map(log -> {
                String purpose;
                if (log.getOrder() != null) {
                    purpose = "For OrderId#" + log.getOrder().getOrderCode();
                } else {
                    purpose = switch (log.getAction()) {
                        case IMPORT -> "For ImportId#" + log.getReason();
                        case EXPORT -> "Xuất kho thủ công";
                        case ADJUST -> "Điều chỉnh thủ công";
                    };
                }

                return new InventoryLogResponse(
                        log.getId(),
                        log.getIngredient().getName(),
                        log.getCreatedAt(),
                        purpose,
                        log.getQuantity().abs(),  // luôn dương
                        "Completed",
                        log.getIngredient().getUnit()  // ← THÊM DÒNG NÀY: lấy unit từ Ingredient
                );
            });

            log.info("[SELLER] Retrieved {} inventory logs (page {}/{})",
                    responsePage.getTotalElements(), page + 1, responsePage.getTotalPages());

            return ResponseEntity.ok(
                    ApiResponse.success(responsePage, "Inventory logs retrieved successfully")
            );
        } catch (Exception e) {
            log.error("[SELLER] Error retrieving inventory logs", e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }

    // Lấy tất cả khách hàng
    @GetMapping("/customers")
    public ResponseEntity<ApiResponse<List<CustomerResponse>>> getAllCustomers() {
        try {
            List<CustomerResponse> customers = customerService.getAllActiveCustomers();
            log.info("✅ Retrieved {} customers", customers.size());
            return ResponseEntity.ok(ApiResponse.success(customers, "Customers retrieved successfully"));
        } catch (Exception e) {
            log.error("❌ Failed to get customers", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // Tìm kiếm khách hàng (phân trang)
    @GetMapping("/customers/search")
    public ResponseEntity<ApiResponse<Page<CustomerResponse>>> searchCustomers(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "name") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {
        try {
            Sort sort = sortDir.equalsIgnoreCase("asc")
                    ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
            Pageable pageable = PageRequest.of(page, size, sort);
            Page<CustomerResponse> result = customerService.searchCustomers(
                    keyword != null ? keyword : "", pageable);
            log.info("✅ Found {} customers for keyword: '{}'", result.getTotalElements(), keyword);
            return ResponseEntity.ok(ApiResponse.success(result, "Customers retrieved successfully"));
        } catch (Exception e) {
            log.error("❌ Failed to search customers", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** GET /api/seller/customers/phone/{phone} — tìm theo SĐT */
    @GetMapping("/customers/phone/{phone}")
    public ResponseEntity<ApiResponse<CustomerResponse>> getCustomerByPhone(
            @PathVariable String phone) {
        try {
            CustomerResponse customer = customerService.getCustomerByPhone(phone);
            log.info("✅ Retrieved customer by phone: {}", phone);
            return ResponseEntity.ok(ApiResponse.success(customer, "Customer retrieved successfully"));
        } catch (RuntimeException e) {
            log.warn("❌ Customer not found with phone: {}", phone);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to get customer by phone: {}", phone, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** GET /api/seller/customers/{id} — lấy theo ID */
    @GetMapping("/customers/{id}")
    public ResponseEntity<ApiResponse<CustomerResponse>> getCustomerById(@PathVariable Long id) {
        try {
            CustomerResponse customer = customerService.getCustomerById(id);
            log.info("✅ Retrieved customer ID: {}", id);
            return ResponseEntity.ok(ApiResponse.success(customer, "Customer retrieved successfully"));
        } catch (RuntimeException e) {
            log.warn("❌ Customer not found ID: {}", id);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to get customer ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** POST /api/seller/customers — tạo khách hàng mới */
    @PostMapping("/customers")
    public ResponseEntity<ApiResponse<CustomerResponse>> createCustomer(
            @Valid @RequestBody CreateCustomerRequest request) {
        try {
            CustomerResponse customer = customerService.createCustomer(request);
            log.info("✅ Created customer: {} (ID: {}, Phone: {})",
                    customer.getName(), customer.getId(), customer.getPhone());
            return ResponseEntity.ok(ApiResponse.success(customer, "Customer created successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid customer request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Failed to create customer", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error creating customer", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** PUT /api/seller/customers/{id} — cập nhật khách hàng */
    @PutMapping("/customers/{id}")
    public ResponseEntity<ApiResponse<CustomerResponse>> updateCustomer(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCustomerRequest request) {
        try {
            CustomerResponse customer = customerService.updateCustomer(id, request);
            log.info("✅ Updated customer: {} (ID: {})", customer.getName(), id);
            return ResponseEntity.ok(ApiResponse.success(customer, "Customer updated successfully"));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid customer update request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Failed to update customer ID: {}", id, e);
            if (e.getMessage().contains("Không tìm thấy")) {
                return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
            }
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error updating customer ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** DELETE /api/seller/customers/{id} — xóa mềm */
    @DeleteMapping("/customers/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteCustomer(@PathVariable Long id) {
        try {
            customerService.deleteCustomer(id);
            log.info("✅ Deleted customer ID: {}", id);
            return ResponseEntity.ok(ApiResponse.success(null, "Customer deleted successfully"));
        } catch (RuntimeException e) {
            log.warn("❌ Customer not found ID: {}", id);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to delete customer ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/all-categories")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getAllCategories() {
        try {
            List<CategoryResponse> categories = categoryService.getAllCategories();
            return ResponseEntity.ok(ApiResponse.success(categories, "Categories retrieved successfully"));
        } catch (Exception e) {
            log.error("❌ Failed to get categories", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getAllCategories(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            List<CategoryResponse> categories =
                    categoryService.getPaginationCategories(page, size);
            return ResponseEntity.ok(
                    ApiResponse.success(categories, "Categories retrieved successfully"));
        } catch (Exception e) {
            log.error("❌ Failed to get categories", e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /**
     * Lấy chi tiết danh mục
     * GET /api/seller/categories/{id}
     */
    @GetMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> getCategoryById(@PathVariable Long id) {
        try {
            CategoryResponse category = categoryService.getCategoryById(id);
            return ResponseEntity.ok(ApiResponse.success(category, "Category retrieved successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }


    /**
     * Tạo danh mục mới
     * POST /api/seller/categories
     */
    @PostMapping("/categories")
    public ResponseEntity<ApiResponse<CategoryResponse>> createCategory(
            @Valid @RequestBody CreateCategoryRequest request) {
        try {
            CategoryResponse category = categoryService.createCategory(request);
            log.info("✅ Created category: {}", category.getName());
            return ResponseEntity.ok(ApiResponse.success(category, "Category created successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to create category", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /**
     * Cập nhật danh mục
     * PUT /api/seller/categories/{id}
     */
    @PutMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> updateCategory(
            @PathVariable Long id,
            @Valid @RequestBody CreateCategoryRequest request) {
        try {
            CategoryResponse category = categoryService.updateCategory(id, request);
            log.info("✅ Updated category ID: {}", id);
            return ResponseEntity.ok(ApiResponse.success(category, "Category updated successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to update category ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PutMapping("/products/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody CreateCompleteProductRequest request) {

        try {
            ProductResponse product = productService.updateProduct(id, request);
            log.info("✅ Updated product: {} (ID: {})", product.getName(), id);

            return ResponseEntity.ok(
                    ApiResponse.success(product, "Product updated successfully")
            );
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request for update product: {}", e.getMessage());
            return ResponseEntity.badRequest().body(
                    ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage())
            );
        } catch (RuntimeException e) {
            log.error("Failed to update product ID: {}", id, e);
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.ok(
                        ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage())
                );
            }
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage())
            );
        } catch (Exception e) {
            log.error("Unexpected error updating product ID: {}", id, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }

    /**
     * Xóa danh mục (soft delete)
     * DELETE /api/seller/categories/{id}
     */
    @DeleteMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteCategory(@PathVariable Long id) {
        try {
            categoryService.deleteCategory(id);
            log.info("✅ Deleted category ID: {}", id);
            return ResponseEntity.ok(ApiResponse.success(null, "Category deleted successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to delete category ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    private final TelegramService telegramService;

    @GetMapping("/orders/{orderId}/invoice")
    public ResponseEntity<Map<String, Object>> generateInvoice(@PathVariable Long orderId) {
        try {
            // Lấy order từ service
            OrderResponse order = orderService.getOrderById(orderId);

            // Map sang InvoiceDTO (giữ nguyên)
            InvoiceDTO invoiceDTO = InvoiceDTO.builder()
                    .orderId(order.getId())
                    .orderCode(order.getOrderCode())
                    .customerName(order.getCustomerName())
                    .customerPhone(order.getCustomerPhone())
                    .customerEmail(order.getCustomerEmail())
                    .shippingAddress(order.getShippingAddress())
                    .notes(order.getNotes())
                    .totalAmount(order.getTotalAmount())
                    .discountAmount(order.getDiscountAmount())
                    .finalAmount(order.getFinalAmount())
                    .vatAmount(order.getVatAmount())
                    .status(order.getStatus())
                    .paymentStatus(order.getPaymentStatus())
                    .paymentMethod(order.getPaymentMethod())
                    .createdAt(order.getCreatedAt())
                    .items(order.getItems().stream()
                            .map(item -> InvoiceDTO.Item.builder()
                                    .productName(item.getProductName())
                                    .variantName(item.getVariantName())
                                    .priceName(item.getPriceName())
                                    .unitPrice(item.getUnitPrice())
                                    .quantity(item.getQuantity())
                                    .subtotal(item.getSubtotal())
                                    .unit(item.getUnit())
                                    .defaultPrice(item.getDefaultPrice())
                                    .ingredientsUsed(item.getIngredientsUsed().stream()
                                            .map(ing -> InvoiceDTO.Ingredient.builder()
                                                    .ingredientName(ing.getIngredientName())
                                                    .quantityUsed(ing.getQuantityUsed())
                                                    .unit(ing.getUnit())
                                                    .build())
                                            .collect(Collectors.toList()))
                                    .build())
                            .collect(Collectors.toList()))
                    .build();

            // Tính VAT breakdown (thêm vào đây)
            Map<Integer, BigDecimal> vatBreakdown = new LinkedHashMap<>(); // giữ thứ tự tăng dần % nếu cần
            for (var item : order.getItems()) {
                Integer rate = item.getVatRate();
                BigDecimal amount = item.getVatAmount();
                if (rate != null && rate > 0 && amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    vatBreakdown.merge(rate, amount, BigDecimal::add);
                }
            }
            invoiceDTO.setVatBreakdown(vatBreakdown);

            // Generate PDF
            byte[] pdfBytes = invoicePdf.GenerateInvoicePdf(invoiceDTO);

            String filename = "invoice_" + order.getOrderCode() + ".pdf";

            // Caption đẹp, hỗ trợ HTML
            String caption = String.format(
                    "📄 HÓA ĐƠN ĐƠN HÀNG %s\n" +
                            "Khách hàng: %s\n" +
                            "SĐT: %s\n" +
                            "Tổng tiền: %s đ\n" +
                            "Thanh toán: %s | %s\n" +
                            "Ngày tạo: %s",
                    order.getOrderCode(),
                    order.getCustomerName() != null ? order.getCustomerName() : "Khách lẻ",
                    order.getCustomerPhone() != null ? order.getCustomerPhone() : "—",
                    formatCurrency(order.getFinalAmount()),
                    order.getPaymentMethod() != null ? order.getPaymentMethod() : "—",
                    order.getPaymentStatus(),
                    formatDate(order.getCreatedAt())
            );

            telegramService.sendDocumentByGroupName(
                    "seller",
                    pdfBytes,
                    filename,
                    caption,
                    null
            );

            // Trả về JSON cho app Flutter
            Map<String, Object> response = new HashMap<>();
            response.put("code", 900);
            response.put("message", "Đã tạo và gửi hóa đơn qua Telegram");
            response.put("orderId", orderId);
            response.put("orderCode", order.getOrderCode());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Lỗi generate/send invoice cho order {}: {}", orderId, e.getMessage(), e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", 500);
            errorResponse.put("message", "Lỗi hệ thống khi xử lý hóa đơn: " + e.getMessage());

            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    // ── Helper methods (thêm vào class SellerController) ──
    private static String formatCurrency(BigDecimal amount) {
        if (amount == null) return "0 đ";
        return String.format("%,.0f đ", amount);
    }

    private static String formatDate(Long epochMillis) {
        if (epochMillis == null || epochMillis == 0) return "N/A";
        return new java.text.SimpleDateFormat("dd/MM/yyyy - HH:mm:ss")
                .format(new java.util.Date(epochMillis));
    }

    @PostMapping("/cart/validate-items")
    public ResponseEntity<ApiResponse<List<CartItemValidateResponse>>> validateCartItems(
            @RequestBody List<CartItemValidateRequest> items) {

        List<CartItemValidateResponse> updates = new ArrayList<>();

        for (CartItemValidateRequest item : items) {
            try {
                ProductResponse prodResp = productService.getProductById(item.getProductId());

                boolean changed = false;
                StringBuilder message = new StringBuilder();

                // So sánh tên sản phẩm
                if (!prodResp.getName().equals(item.getProductName())) {
                    changed = true;
                    message.append("Tên sản phẩm thay đổi. ");
                }

                // So sánh unit
                if (!prodResp.getUnit().equals(item.getUnit())) {
                    changed = true;
                    message.append("Đơn vị thay đổi. ");
                }

                // So sánh ảnh
                if (!prodResp.getImageUrl().equals(item.getImageUrl())) {
                    changed = true;
                    message.append("Ảnh sản phẩm thay đổi. ");
                }

                // So sánh variant (nếu có)
                boolean variantChanged = false;
                ProductResponse.VariantResponse matchedVariant = null;
                if (item.getVariantId() != null) {
                    for (ProductResponse.VariantResponse v : prodResp.getVariants()) {
                        if (v.getId().equals(item.getVariantId())) {
                            matchedVariant = v;
                            if (!v.getVariantName().equals(item.getVariantName())) {
                                variantChanged = true;
                            }
                            break;
                        }
                    }
                    if (matchedVariant == null) {
                        variantChanged = true;
                        message.append("Biến thể không còn tồn tại. ");
                    } else if (variantChanged) {
                        changed = true;
                        message.append("Tên biến thể thay đổi. ");
                    }
                }

                // So sánh giá
                boolean priceChanged = true;
                ProductResponse.PriceResponse matchedPrice = null;
                for (ProductResponse.PriceResponse p : prodResp.getPrices()) {
                    if (p.getId().equals(item.getPriceId())) {
                        matchedPrice = p;
                        if (p.getPriceName().equals(item.getPriceName()) &&
                                p.getPrice().compareTo(item.getPrice()) == 0) {
                            priceChanged = false;
                        }
                        break;
                    }
                }

                if (priceChanged) {
                    changed = true;
                    message.append("Mức giá hoặc tên giá thay đổi. ");
                }

                if (changed) {
                    updates.add(CartItemValidateResponse.builder()
                            .productId(prodResp.getId())
                            .status("UPDATED")
                            .message(message.toString().trim())
                            .productName(prodResp.getName())
                            .unit(prodResp.getUnit())
                            .imageUrl(prodResp.getImageUrl())
                            .priceId(matchedPrice != null ? matchedPrice.getId() : null)
                            .priceName(matchedPrice != null ? matchedPrice.getPriceName() : null)
                            .price(matchedPrice != null ? matchedPrice.getPrice() : null)
                            .variantId(matchedVariant != null ? matchedVariant.getId() : null)
                            .variantName(matchedVariant != null ? matchedVariant.getVariantName() : null)
                            .build());
                } else {
                    updates.add(CartItemValidateResponse.builder()
                            .productId(prodResp.getId())
                            .status("UNCHANGED")
                            .build());
                }

            } catch (Exception e) {
                updates.add(CartItemValidateResponse.builder()
                        .productId(item.getProductId())
                        .status("REMOVED")
                        .message("Sản phẩm không còn tồn tại")
                        .build());
            }
        }

        return ResponseEntity.ok(ApiResponse.success(updates, "Kiểm tra giỏ hàng hoàn tất"));
    }

    @GetMapping("/all-ingredients")
    public ResponseEntity<ApiResponse<List<IngredientResponse>>> getAllIngredients() {
        try {
            List<IngredientResponse> ingredients = ingredientService.getAllIngredients();
            log.info("✅ Retrieved {} ingredients", ingredients.size());
            return ResponseEntity.ok(
                    ApiResponse.success(ingredients, "Ingredients retrieved successfully")
            );
        } catch (Exception e) {
            log.error("❌ Failed to get ingredients", e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }

    @GetMapping("/ingredients")
    public ResponseEntity<ApiResponse<List<IngredientResponse>>> getPaginationIngredients(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            List<IngredientResponse> ingredients =
                    ingredientService.getPaginationIngredients(page, size);
            log.info("✅ Retrieved {} ingredients (page={}, size={})",
                    ingredients.size(), page, size);
            return ResponseEntity.ok(
                    ApiResponse.success(ingredients, "Ingredients retrieved successfully")
            );
        } catch (Exception e) {
            log.error("❌ Failed to get ingredients", e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }

    /**
     * Lấy ingredient theo ID
     * GET /api/seller/ingredients/{id}
     */
    @GetMapping("/ingredients/{id}")
    public ResponseEntity<ApiResponse<IngredientResponse>> getIngredientById(@PathVariable Long id) {
        try {
            IngredientResponse ingredient = ingredientService.getIngredientById(id);
            log.info("✅ Retrieved ingredient: {} (ID: {})", ingredient.getName(), id);

            return ResponseEntity.ok(
                    ApiResponse.success(ingredient, "Ingredient retrieved successfully")
            );
        } catch (RuntimeException e) {
            log.error("❌ Ingredient not found ID: {}", id, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage())
            );
        } catch (Exception e) {
            log.error("❌ Failed to get ingredient ID: {}", id, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }

    /**
     * Tạo ingredient mới
     * POST /api/seller/ingredients
     */
    @PostMapping("/ingredients")
    public ResponseEntity<ApiResponse<IngredientResponse>> createIngredient(
            @Valid @RequestBody CreateIngredientRequest request) {

        try {
            IngredientResponse ingredient = ingredientService.createIngredient(request);
            log.info("✅ Created ingredient: {} (ID: {})", ingredient.getName(), ingredient.getId());

            return ResponseEntity.ok(
                    ApiResponse.success(ingredient, "Ingredient created successfully")
            );
        } catch (Exception e) {
            log.error("❌ Failed to create ingredient", e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage())
            );
        }
    }

    /**
     * Cập nhật ingredient
     * PUT /api/seller/ingredients/{id}
     */
    @PutMapping("/ingredients/{id}")
    public ResponseEntity<ApiResponse<IngredientResponse>> updateIngredient(
            @PathVariable Long id,
            @Valid @RequestBody CreateIngredientRequest request) {

        try {
            IngredientResponse ingredient = ingredientService.updateIngredient(id, request);
            log.info("✅ Updated ingredient: {} (ID: {})", ingredient.getName(), id);

            return ResponseEntity.ok(
                    ApiResponse.success(ingredient, "Ingredient updated successfully")
            );
        } catch (RuntimeException e) {
            log.error("❌ Ingredient not found ID: {}", id, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage())
            );
        } catch (Exception e) {
            log.error("❌ Failed to update ingredient ID: {}", id, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage())
            );
        }
    }

    /**
     * Xóa ingredient (soft delete)
     * DELETE /api/seller/ingredients/{id}
     */
    @DeleteMapping("/ingredients/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteIngredient(@PathVariable Long id) {
        try {
//            ingredientService.deleteIngredient(id);
//            log.info("✅ Deleted ingredient ID: {}", id);

            return ResponseEntity.ok(
                    ApiResponse.success(null, "Ingredient deleted successfully")
            );
        } catch (RuntimeException e) {
            log.error("❌ Ingredient not found ID: {}", id, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage())
            );
        } catch (Exception e) {
            log.error("❌ Failed to delete ingredient ID: {}", id, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }

    // ==================== PRODUCT MANAGEMENT (WITH IMAGE UPLOAD) ====================

    /**
     * Tạo sản phẩm mới (có upload ảnh)
     * POST /api/seller/products
     * Content-Type: multipart/form-data
     */
    @PostMapping("/products")
    public ResponseEntity<ApiResponse<ProductResponse>> createCompleteProduct(
            @Valid @RequestBody CreateCompleteProductRequest request) {
        try {
            ProductResponse product = productService.createCompleteProduct(request);
            log.info("✅ Created complete product: {} (ID: {})", product.getName(), product.getId());

            return ResponseEntity.ok(
                    ApiResponse.success(product, "Product created successfully")
            );
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request for complete product: {}", e.getMessage());
            return ResponseEntity.badRequest().body(
                    ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage())
            );
        } catch (RuntimeException e) {
            log.error("Failed to create product", e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage())
            );
        } catch (Exception e) {
            log.error("Unexpected error creating product", e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }

    @DeleteMapping("/products/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteProduct(@PathVariable Long id) {
        try {
            // Lấy thông tin sản phẩm để xóa ảnh
            ProductResponse product = productService.getProductById(id);
            String imageUrl = product.getImageUrl();

            // Xóa sản phẩm (soft delete)
            productService.deleteProduct(id);

            // Xóa ảnh
            if (imageUrl != null && !imageUrl.isEmpty()) {
                try {
                    fileStorageService.deleteFile(imageUrl);
                    log.info("✅ Product image deleted: {}", imageUrl);
                } catch (IOException e) {
                    log.warn("⚠️ Failed to delete product image: {}", imageUrl);
                }
            }

            log.info("✅ Product deleted: {} (ID: {})", product.getName(), id);

            return ResponseEntity.ok(
                    ApiResponse.success(null, "Product deleted successfully")
            );

        } catch (RuntimeException e) {
            log.error("❌ Product not found ID: {}", id, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage())
            );
        } catch (Exception e) {
            log.error("❌ Failed to delete product ID: {}", id, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }

    /**
     * Thêm variant cho sản phẩm (có upload ảnh)
     * POST /api/seller/variants
     * Content-Type: multipart/form-data
     */
    @PostMapping(value = "/variants", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProductResponse>> addVariant(
            @RequestParam("productId") Long productId,
            @RequestParam("variantName") String variantName,
            @RequestParam("variantCode") String variantCode,
            @RequestParam(value = "isDefault", required = false, defaultValue = "false") Boolean isDefault,
            @RequestParam(value = "displayOrder", required = false, defaultValue = "0") Integer displayOrder,
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "ingredients", required = false) String ingredientsJson) {

        try {
            // Upload ảnh variant nếu có
            String imageUrl = null;
            if (image != null && !image.isEmpty()) {
                imageUrl = fileStorageService.saveVariantImage(image);
                log.info("✅ Variant image uploaded: {}", imageUrl);
            }

            // Tạo request (cần parse ingredientsJson nếu có)
            CreateVariantRequest request = new CreateVariantRequest();
            request.setProductId(productId);
            request.setVariantName(variantName);
            request.setVariantCode(variantCode);
            request.setIsDefault(isDefault != null && isDefault);
            request.setDisplayOrder(displayOrder != null ? displayOrder : 0);
            // TODO: Parse ingredientsJson to List<VariantIngredientItem> nếu cần

            // Thêm variant
            ProductResponse product = productService.addVariant(request);

            log.info("✅ Variant added: {} to product ID: {}", variantName, productId);

            return ResponseEntity.ok(
                    ApiResponse.success(product, "Variant added successfully")
            );

        } catch (IOException e) {
            log.error("❌ Failed to upload variant image", e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR,
                            "Failed to upload image: " + e.getMessage())
            );
        } catch (RuntimeException e) {
            log.error("❌ Failed to add variant", e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage())
            );
        } catch (Exception e) {
            log.error("❌ Failed to add variant", e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage())
            );
        }
    }

    /**
     * Xóa variant (xóa ảnh nếu có)
     * DELETE /api/seller/variants/{variantId}
     */
    @DeleteMapping("/variants/{variantId}")
    public ResponseEntity<ApiResponse<Object>> deleteVariant(@PathVariable Long variantId) {
        try {
            // TODO: Lấy thông tin variant để xóa ảnh nếu có
            // ProductVariant variant = variantRepository.findById(variantId)...
            // if (variant.getImageUrl() != null) { fileStorageService.deleteFile(variant.getImageUrl()); }

            // Xóa variant
            productService.deleteVariant(variantId);

            log.info("✅ Variant deleted ID: {}", variantId);

            return ResponseEntity.ok(
                    ApiResponse.success(null, "Variant deleted successfully")
            );

        } catch (RuntimeException e) {
            log.error("❌ Variant not found ID: {}", variantId, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage())
            );
        } catch (Exception e) {
            log.error("❌ Failed to delete variant ID: {}", variantId, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }


    // ==================== PRODUCT LISTING ====================

    /**
     * Lấy danh sách sản phẩm (có phân trang)
     * GET /api/seller/products?page=0&size=10&sort=name,asc
     */
    @GetMapping("/products")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "name") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir,
            @RequestParam(required = false) String category) {
        try {
            // Tạo pageable
            Sort sort = sortDir.equalsIgnoreCase("asc")
                    ? Sort.by(sortBy).ascending()
                    : Sort.by(sortBy).descending();
            Pageable pageable = PageRequest.of(page, size, sort);

            // Lấy danh sách sản phẩm
            List<ProductResponse> products;
            if (category != null && !category.isEmpty()) {
                products = productService.getProductsByCategory(category);
            } else {
                products = productService.getAllProducts();
            }

            // Tính toán phân trang thủ công
            int start = (int) pageable.getOffset();
            int end = Math.min((start + pageable.getPageSize()), products.size());
            List<ProductResponse> pageContent = products.subList(start, end);

            // Tạo response data
            Map<String, Object> data = new HashMap<>();
            data.put("content", pageContent);
            data.put("currentPage", page);
            data.put("totalItems", products.size());
            data.put("totalPages", (int) Math.ceil((double) products.size() / size));
            data.put("pageSize", size);

            log.info("[SELLER] Retrieved {} products (page {}/{})",
                    pageContent.size(), page + 1, data.get("totalPages"));

            return ResponseEntity.ok(
                    ApiResponse.success(data, "Products retrieved successfully")
            );
        } catch (Exception e) {
            log.error("[SELLER] Error retrieving products", e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }

    /**
     * Lấy chi tiết sản phẩm (bao gồm variants và prices)
     * GET /api/seller/products/{id}
     */
    @GetMapping("/products/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> getProductDetail(@PathVariable Long id) {
        try {
            ProductResponse product = productService.getProductById(id);
            log.info("[SELLER] Retrieved product detail: {} (ID: {})", product.getName(), id);
            return ResponseEntity.ok(
                    ApiResponse.success(product, "Product detail retrieved successfully")
            );
        } catch (RuntimeException e) {
            log.error("[SELLER] Product not found ID: {}", id, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage())
            );
        } catch (Exception e) {
            log.error("[SELLER] Error getting product detail ID: {}", id, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }

    /**
     * Lấy danh sách variants của sản phẩm
     * GET /api/seller/products/{id}/variants
     */
    @GetMapping("/products/{id}/variants")
    public ResponseEntity<ApiResponse<List<ProductResponse.VariantResponse>>> getProductVariants(
            @PathVariable Long id) {
        try {
            ProductResponse product = productService.getProductById(id);
            List<ProductResponse.VariantResponse> variants = product.getVariants();

            log.info("[SELLER] Retrieved {} variants for product ID: {}", variants.size(), id);
            return ResponseEntity.ok(
                    ApiResponse.success(variants, "Variants retrieved successfully")
            );
        } catch (RuntimeException e) {
            log.error("[SELLER] Product not found ID: {}", id, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage())
            );
        } catch (Exception e) {
            log.error("[SELLER] Error getting variants for product ID: {}", id, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }

    /**
     * Lấy danh sách prices của sản phẩm/variant
     * GET /api/seller/products/{id}/prices?variantId=...
     */
    @GetMapping("/products/{id}/prices")
    public ResponseEntity<ApiResponse<List<ProductResponse.PriceResponse>>> getProductPrices(
            @PathVariable Long id,
            @RequestParam(required = false) Long variantId) {
        try {
            ProductResponse product = productService.getProductById(id);
            List<ProductResponse.PriceResponse> prices = product.getPrices();

            log.info("[SELLER] Retrieved {} prices for product ID: {} (variantId: {})",
                    prices.size(), id, variantId);
            return ResponseEntity.ok(
                    ApiResponse.success(prices, "Prices retrieved successfully")
            );
        } catch (RuntimeException e) {
            log.error("[SELLER] Product not found ID: {}", id, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage())
            );
        } catch (Exception e) {
            log.error("[SELLER] Error getting prices for product ID: {}", id, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }

    // ==================== ORDER MANAGEMENT ====================

    /**
     * Tạo đơn hàng mới (có lock để tránh race condition)
     * POST /api/seller/orders
     */
    @PostMapping("/orders")
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            Authentication authentication) {

        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("[SELLER] Unauthorized order creation attempt");
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.UNAUTHORIZED, "Unauthorized")
            );
        }

        User user = (User) authentication.getPrincipal();
        Long userId = user.getId();

        log.info("[SELLER] Order creation request from user: {} (ID: {})",
                user.getUsername(), userId);

        // Lấy lock để tránh tạo nhiều đơn hàng cùng lúc
        ReentrantLock lock = transactionLockManager.getLock(userId);

        if (!lock.tryLock()) {
            log.warn("[SELLER] Lock denied - Another order in progress for user: {}", userId);
            return ResponseEntity.ok(
                    ApiResponse.error(
                            StatusCode.TOO_MANY_REQUESTS,
                            "Another order is being processed. Please wait and try again."
                    )
            );
        }

        try {
            OrderResponse order = orderService.createOrder(request, userId);
            log.info("[SELLER] ✅ Order created: {} (Total: {}đ)",
                    order.getOrderCode(), order.getFinalAmount());

            return ResponseEntity.ok(
                    ApiResponse.success(order, "Order created successfully")
            );
        } catch (PriceChangedException e) {
            // Giá sản phẩm đã thay đổi so với lúc UI load — yêu cầu user thao tác lại
            log.warn("[SELLER] Price mismatch for user={}: {}", userId, e.getMessage());
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.PRICE_CHANGED, e.getMessage())
            );
        } catch (RuntimeException e) {
            log.error("[SELLER] Order creation failed for user: {}", userId, e);
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.ok(
                        ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
            } else if (e.getMessage().contains("Insufficient stock")
                    || e.getMessage().contains("Không đủ tồn kho")) {
                return ResponseEntity.ok(
                        ApiResponse.error(StatusCode.OUT_OF_STOCK, e.getMessage()));
            } else {
                return ResponseEntity.ok(
                        ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
            }
        } catch (Exception e) {
            log.error("[SELLER] Unexpected error creating order for user: {}", userId, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR,
                            "Failed to create order: " + e.getMessage())
            );
        } finally {
            lock.unlock();
            transactionLockManager.cleanupIfUnused(userId);
            log.info("[SELLER] Lock released for user: {}", userId);
        }
    }

    /**
     * Kiểm tra đơn hàng theo ID
     * GET /api/seller/orders/{orderId}
     */
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @PathVariable Long orderId,
            Authentication authentication) {
        try {
            OrderResponse order = orderService.getOrderById(orderId);

            return ResponseEntity.ok(
                    ApiResponse.success(order, "Order retrieved successfully")
            );
        } catch (RuntimeException e) {
            log.error("[SELLER] Order not found ID: {}", orderId, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage())
            );
        } catch (Exception e) {
            log.error("[SELLER] Error getting order ID: {}", orderId, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }

    /**
     * Kiểm tra đơn hàng theo mã đơn
     * GET /api/seller/orders/code/{orderCode}
     */
    @GetMapping("/orders/code/{orderCode}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderByCode(
            @PathVariable String orderCode,
            Authentication authentication) {
        try {
            OrderResponse order = orderService.getOrderByCode(orderCode);

            // Kiểm tra quyền xem đơn hàng
            User user = (User) authentication.getPrincipal();
            if (!user.getRole().equals("ADMIN")) {
                log.warn("[SELLER] User {} attempted to access order {} (unauthorized)",
                        user.getId(), orderCode);
                return ResponseEntity.ok(
                        ApiResponse.error(StatusCode.FORBIDDEN,
                                "You don't have permission to access this order")
                );
            }

            log.info("[SELLER] Retrieved order: {}", order.getOrderCode());
            return ResponseEntity.ok(
                    ApiResponse.success(order, "Order retrieved successfully")
            );
        } catch (RuntimeException e) {
            log.error("[SELLER] Order not found: {}", orderCode, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage())
            );
        } catch (Exception e) {
            log.error("[SELLER] Error getting order: {}", orderCode, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }

    /**
     * Lấy danh sách đơn hàng của mình
     * GET /api/seller/orders/my-orders
     */
    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getMyOrders(
            Authentication authentication) {
        try {
            User user = (User) authentication.getPrincipal();
            List<OrderResponse> orders = orderService.getMyOrders(user.getId());

            log.info("[SELLER] Retrieved {} orders for user: {}", orders.size(), user.getUsername());
            return ResponseEntity.ok(
                    ApiResponse.success(orders, "Orders retrieved successfully")
            );
        } catch (Exception e) {
            log.error("[SELLER] Error getting orders", e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }

    /**
     * Hủy đơn hàng (khôi phục kho nguyên liệu)
     * POST /api/seller/orders/{orderId}/cancel
     */
    @PostMapping("/orders/{orderId}/cancel")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @PathVariable Long orderId,
            Authentication authentication) {
        try {
            User user = (User) authentication.getPrincipal();
            OrderResponse order = orderService.cancelOrder(orderId, user.getId());

            log.info("[SELLER] Order cancelled: {} by user: {}",
                    order.getOrderCode(), user.getUsername());
            return ResponseEntity.ok(
                    ApiResponse.success(order, "Order cancelled successfully")
            );
        } catch (RuntimeException e) {
            log.error("[SELLER] Error cancelling order ID: {}", orderId, e);

            if (e.getMessage().contains("not found")) {
                return ResponseEntity.ok(
                        ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage())
                );
            } else {
                return ResponseEntity.ok(
                        ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage())
                );
            }
        } catch (Exception e) {
            log.error("[SELLER] Error cancelling order ID: {}", orderId, e);
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
            );
        }
    }

    // ==================== TEST LOCK (Keep for testing) ====================

    @PostMapping("/test-lock")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testLock(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("[TEST-LOCK] Unauthorized access attempt");
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.UNAUTHORIZED, "Unauthorized")
            );
        }

        User user = (User) authentication.getPrincipal();
        Long userId = user.getId();

        log.info("[TEST-LOCK] Request from userId: {}, username: {}", userId, user.getUsername());

        ReentrantLock lock = transactionLockManager.getLock(userId);

        if (lock.tryLock()) {
            try {
                log.info("[TEST-LOCK] Lock acquired for userId: {}", userId);
                Thread.sleep(5000);
                log.info("[TEST-LOCK] Done - Order created successfully for userId: {}", userId);

                Map<String, Object> data = new HashMap<>();
                data.put("userId", userId);
                data.put("username", user.getUsername());
                data.put("processTime", "5 seconds");

                return ResponseEntity.ok(
                        ApiResponse.success(data, "Order created successfully")
                );

            } catch (InterruptedException e) {
                log.error("[TEST-LOCK] Thread interrupted for userId: {}", userId, e);
                Thread.currentThread().interrupt();
                return ResponseEntity.ok(
                        ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, "Process interrupted")
                );
            } catch (Exception e) {
                log.error("[TEST-LOCK] Unexpected error for userId: {}", userId, e);
                return ResponseEntity.ok(
                        ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, "Order creation failed")
                );
            } finally {
                lock.unlock();
                transactionLockManager.cleanupIfUnused(userId);
                log.info("[TEST-LOCK] Lock released for userId: {}", userId);
            }
        } else {
            log.warn("[TEST-LOCK] Lock denied - Another transaction in progress for userId: {}", userId);
            return ResponseEntity.ok(
                    ApiResponse.error(
                            StatusCode.TOO_MANY_REQUESTS,
                            "Another transaction is being processed. Please wait and try again."
                    )
            );
        }
    }
}