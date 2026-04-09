package com.nhatnam.server.service;

import com.nhatnam.server.dto.pos.*;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.entity.pos.*;
import com.nhatnam.server.enumtype.*;
import com.nhatnam.server.repository.pos.*;
import com.nhatnam.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import com.nhatnam.server.entity.pos.PosCustomer;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class PosService {

    private final PosCategoryRepository   categoryRepo;
    private final PosIngredientRepository ingredientRepo;
    private final PosProductRepository    productRepo;
    private final PosVariantRepository    variantRepo;
    private final PosVariantIngredientRepository variantIngredientRepo;
    private final PosAppMenuRepository    appMenuRepo;
    private final PosShiftRepository      shiftRepo;
    private final PosShiftDenominationRepository      openDenomRepo;
    private final PosShiftCloseDenominationRepository closeDenomRepo;
    private final PosShiftOpenInventoryRepository     openInvRepo;
    private final PosShiftCloseInventoryRepository    closeInvRepo;
    private final PosOrderRepository      orderRepo;
    private final PosOrderItemRepository  orderItemRepo;
    private final PosOrderItemIngredientRepository orderItemIngredientRepo;
    private final UserRepository          userRepo;
    private final PosShiftStockImportRepository stockImportRepo;
    private final PosUserStoreRepository posUserStoreRepo;
    private final PosStoreRepository     posStoreRepo;
    private final PosDiscountService           posDiscountService;
    private final PosCustomerRepository        posCustomerRepo;
    private final PosCustomerDiscountRepository customerDiscountRepo;

    @Transactional
    public void deleteOrder(Long orderId) {
        PosOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng #" + orderId));
        // PosOrderItem có cascade = CascadeType.ALL → JPA tự xóa items
        orderRepo.delete(order);
    }

    // ════════════════════════════════════════
    // STOCK IMPORT
    // ════════════════════════════════════════

    public List<StockImportHistoryResponse> getStockImportHistory(Long userId, Long storeId) {
        // Lấy ca OPEN của store (không nhất thiết user hiện tại mở)
        Optional<PosShift> shiftOpt = shiftRepo.findOpenShiftByStoreId(storeId, userId, ShiftStatus.OPEN);
        if (shiftOpt.isEmpty()) return Collections.emptyList();

        PosShift shift = shiftOpt.get();
        List<PosShiftStockImport> allImports =
                stockImportRepo.findByShift_IdOrderByImportedAtDesc(shift.getId());
        if (allImports.isEmpty()) return Collections.emptyList();

        Map<Long, List<PosShiftStockImport>> grouped = allImports.stream()
                .collect(Collectors.groupingBy(
                        PosShiftStockImport::getImportedAt,
                        LinkedHashMap::new, Collectors.toList()));

        List<Long> sortedTs = new ArrayList<>(grouped.keySet());
        sortedTs.sort(Comparator.reverseOrder());

        List<StockImportHistoryResponse> result = new ArrayList<>();
        for (int i = 0; i < sortedTs.size(); i++) {
            Long ts = sortedTs.get(i);
            List<PosShiftStockImport> batchItems = grouped.get(ts);
            List<StockImportHistoryResponse.StockImportItemDetail> details = batchItems.stream()
                    .map(imp -> StockImportHistoryResponse.StockImportItemDetail.builder()
                            .id(imp.getId())
                            .ingredientId(imp.getIngredientId())
                            .ingredientName(imp.getIngredientName())
                            .ingredientImageUrl("")
                            .ingredientType(imp.getIngredientTypeName())
                            .packQty(imp.getPackQty())
                            .unitPerPack(imp.getUnitPerPack())
                            .importedAt(imp.getImportedAt())
                            .build())
                    .collect(Collectors.toList());
            result.add(StockImportHistoryResponse.builder()
                    .importedAt(ts).batchIndex(i + 1)
                    .totalItems(batchItems.size()).items(details).build());
        }
        return result;
    }

    @Transactional
    public List<StockImportResponse> importStock(StockImportRequest req, Long userId, Long storeId) {
        // Chỉ user mở ca mới được nhập kho trong ca đó
        PosShift shift = shiftRepo.findOpenShiftByStoreIdAndUserId(storeId, userId, ShiftStatus.OPEN)
                .orElseThrow(() -> new RuntimeException("Chưa mở ca."));
        long now = System.currentTimeMillis();
        List<PosShiftStockImport> imports = new ArrayList<>();
        for (var item : req.getItems()) {
            if (item.getPackQty() <= 0) continue;
            PosIngredient ing = ingredientRepo.findById(item.getIngredientId())
                    .orElseThrow(() -> new RuntimeException("Ingredient not found: " + item.getIngredientId()));
            imports.add(PosShiftStockImport.builder()
                    .shift(shift)
                    .ingredientId(ing.getId())                    // ← snapshot
                    .ingredientName(ing.getName())                // ← snapshot
                    .ingredientTypeName(ing.getIngredientType() != null
                            ? ing.getIngredientType().name() : "MAIN") // ← snapshot
                    .unit(ing.getUnit())                          // ← snapshot
                    .unitPerPack(ing.getUnitPerPack())            // ← snapshot
                    .packQty(item.getPackQty())
                    .importedAt(now)
                    .build());
        }
        return stockImportRepo.saveAll(imports).stream()
                .map(this::toStockImportResponse).collect(Collectors.toList());
    }

    // ════════════════════════════════════════
    // SHIFT CHECK
    // ════════════════════════════════════════

    public boolean isFirstShiftOfDay(String shiftDate, Long storeId) {
        boolean existsClosed = shiftRepo.existsShiftByStoreAndDateAndStatus(
                storeId, shiftDate, ShiftStatus.CLOSED);
        boolean existsAny    = shiftRepo.existsShiftByStoreAndDate(storeId, shiftDate);

        return !existsClosed;
    }


    // ════════════════════════════════════════
    // CATEGORY — lọc theo storeId
    // ════════════════════════════════════════

    public List<PosCategoryResponse> getAllCategories(Long storeId, boolean includeDefault) {
        List<PosCategoryResponse> cats = categoryRepo
                .findByStoreIdAndIsActiveTrueOrderByDisplayOrderAsc(storeId)
                .stream().map(this::toCategoryResponse).collect(Collectors.toList());

        if (includeDefault) {
            long uncategorizedCount = productRepo
                    .findByStoreIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(storeId)
                    .stream().filter(p -> p.getCategory() == null).count();

            if (uncategorizedCount > 0) {
                cats.add(0, PosCategoryResponse.builder()
                        .id(-1L)
                        .name("Mặc định")
                        .imageUrl(null)
                        .displayOrder(-1)
                        .isActive(true)
                        .singlePrice(false)
                        .productCount((int) uncategorizedCount)
                        .build());
            }
        }

        return cats;
    }



    @Transactional
    public PosCategoryResponse createCategory(CreatePosCategoryRequest req, Long storeId) {
        long now = System.currentTimeMillis();

        PosCategory cat = PosCategory.builder()
                .storeId(storeId)                          // ← gán storeId
                .name(req.getName()).imageUrl(req.getImageUrl())
                .displayOrder(req.getDisplayOrder() != null ? req.getDisplayOrder() : 0)
                .singlePrice(Boolean.TRUE.equals(req.getSinglePrice()))
                .storeId(storeId)
                .isActive(true).createdAt(now).updatedAt(now).build();
        return toCategoryResponse(categoryRepo.save(cat));
    }

    @Transactional
    public PosCategoryResponse updateCategory(Long id, UpdatePosCategoryRequest req) {
        PosCategory cat = categoryRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Category not found: " + id));
        if (req.getName() != null)         cat.setName(req.getName());
        if (req.getImageUrl() != null)     cat.setImageUrl(req.getImageUrl());
        if (req.getDisplayOrder() != null) cat.setDisplayOrder(req.getDisplayOrder());
        if (req.getIsActive() != null)     cat.setIsActive(req.getIsActive());
        if (req.getSinglePrice() != null)  cat.setSinglePrice(req.getSinglePrice());
        cat.setUpdatedAt(System.currentTimeMillis());
        return toCategoryResponse(categoryRepo.save(cat));
    }

    @Transactional
    public void deleteCategory(Long id) {
        PosCategory cat = categoryRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Category not found: " + id));

        // Bước 1: Gỡ liên kết với tất cả Product (set category = null)
        List<PosProduct> products = productRepo.findByCategoryId(id);  // cần thêm method này
        for (PosProduct p : products) {
            p.setCategory(null);
        }
        productRepo.saveAll(products);   // hoặc flush nếu muốn

        // Bước 2: Xóa Category
        categoryRepo.delete(cat);
    }

    @Transactional
    public PosCategoryResponse addProductToCategory(Long categoryId, Long productId) {
        PosCategory cat = categoryRepo.findById(categoryId)
                .orElseThrow(() -> new RuntimeException("Category not found: " + categoryId));
        PosProduct product = productRepo.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));
        product.setCategory(cat);
        product.setUpdatedAt(System.currentTimeMillis());
        productRepo.save(product);
        return toCategoryResponse(cat);
    }

    // ════════════════════════════════════════
    // INGREDIENT — lọc theo storeId
    // ════════════════════════════════════════

    public List<PosIngredientResponse> getAllIngredients(Long storeId) {
        return ingredientRepo.findByStoreIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(storeId)
                .stream().map(this::toIngredientResponse).collect(Collectors.toList());
    }

    @Transactional
    public PosIngredientResponse createIngredient(CreatePosIngredientRequest req, Long storeId) {
        long now = System.currentTimeMillis();
        PosIngredient ing = PosIngredient.builder()
                .storeId(storeId)
                .name(req.getName()).imageUrl(req.getImageUrl())
                .unit(req.getUnit() != null && !req.getUnit().isBlank()   // ← THÊM
                        ? req.getUnit() : "Cái")
                .unitPerPack(req.getUnitPerPack() != null ? req.getUnitPerPack() : 1)
                .displayOrder(req.getDisplayOrder() != null ? req.getDisplayOrder() : 0)
                .ingredientType(req.getIngredientType() != null ? req.getIngredientType() : IngredientType.MAIN)
                .addonPrice(req.getAddonPrice() != null ? req.getAddonPrice() : BigDecimal.ZERO)
                .storeId(storeId)
                .isActive(true).createdAt(now).updatedAt(now).build();
        return toIngredientResponse(ingredientRepo.save(ing));
    }

    @Transactional
    public PosIngredientResponse updateIngredient(Long id, CreatePosIngredientRequest req) {
        PosIngredient ing = ingredientRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Ingredient not found: " + id));
        if (req.getName() != null)          ing.setName(req.getName());
        if (req.getImageUrl() != null)      ing.setImageUrl(req.getImageUrl());
        if (req.getUnitPerPack() != null)   ing.setUnitPerPack(req.getUnitPerPack());
        if (req.getDisplayOrder() != null)  ing.setDisplayOrder(req.getDisplayOrder());
        if (req.getIngredientType() != null) ing.setIngredientType(req.getIngredientType());
        if (req.getAddonPrice() != null)    ing.setAddonPrice(req.getAddonPrice());
        if (req.getUnit() != null && !req.getUnit().isBlank()) ing.setUnit(req.getUnit()); // ← THÊM
        if (req.getName() != null)          ing.setName(req.getName());
        ing.setUpdatedAt(System.currentTimeMillis());
        return toIngredientResponse(ingredientRepo.save(ing));
    }

    @Transactional
    public void deleteIngredient(Long id) {
        PosIngredient ing = ingredientRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Ingredient not found: " + id));

        variantIngredientRepo.deleteByIngredientId(id);

        ingredientRepo.delete(ing);
    }

    // ════════════════════════════════════════
    // PRODUCT — lọc theo storeId
    // ════════════════════════════════════════

    public List<PosProductResponse> getProductsByCategory(Long categoryId, Long storeId) {
        // Category ảo "Mặc định" — chứa product không có category
        if (categoryId == -1L) {
            return productRepo
                    .findByStoreIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(storeId)
                    .stream()
                    .filter(p -> p.getCategory() == null)
                    .map(this::toProductResponse)
                    .collect(Collectors.toList());
        }

        PosCategory cat = categoryRepo.findById(categoryId)
                .orElseThrow(() -> new RuntimeException("Category not found: " + categoryId));
        if (!storeId.equals(cat.getStoreId()))
            throw new RuntimeException("Category không thuộc store của bạn.");
        return productRepo.findByCategoryAndIsActiveTrueOrderByDisplayOrderAscNameAsc(cat)
                .stream().map(this::toProductResponse).collect(Collectors.toList());
    }

    public List<PosProductResponse> getAllActiveProducts(Long storeId) {
        return productRepo.findByStoreIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(storeId)
                .stream().map(this::toProductResponse).collect(Collectors.toList());
    }

    public PosProductResponse getProductById(Long id) {
        return toProductResponse(productRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id)));
    }

    @Transactional
    public PosProductResponse createProduct(CreatePosProductRequest req, Long storeId) {
        PosCategory cat = categoryRepo.findById(req.getCategoryId())
                .orElseThrow(() -> new RuntimeException("Category not found: " + req.getCategoryId()));
        // Verify category thuộc store
        if (!storeId.equals(cat.getStoreId()))
            throw new RuntimeException("Category không thuộc store của bạn.");
        long now = System.currentTimeMillis();
        PosProduct p = PosProduct.builder()
                .storeId(storeId)                          // ← gán storeId
                .name(req.getName()).description(req.getDescription()).imageUrl(req.getImageUrl())
                .basePrice(req.getBasePrice()).category(cat)
                .displayOrder(req.getDisplayOrder() != null ? req.getDisplayOrder() : 0)
                .vatPercent(req.getVatPercent() != null ? req.getVatPercent() : 0)
                .isShopeeFood(Boolean.TRUE.equals(req.getIsShopeeFood()))
                .isGrabFood(Boolean.TRUE.equals(req.getIsGrabFood()))
                .storeId(storeId)
                .isActive(true).createdAt(now).updatedAt(now).build();
        p = productRepo.save(p);
        _upsertAppMenu(p, AppPlatform.SHOPEE_FOOD,
                Boolean.TRUE.equals(req.getIsShopeeFood()), req.getShopeePrice());
        _upsertAppMenu(p, AppPlatform.GRAB_FOOD,
                Boolean.TRUE.equals(req.getIsGrabFood()), req.getGrabPrice());
        return toProductResponse(productRepo.findById(p.getId()).orElseThrow());
    }

    @Transactional
    public PosProductResponse updateProduct(Long id, UpdatePosProductRequest req) {
        PosProduct p = productRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));
        if (req.getName() != null)        p.setName(req.getName());
        if (req.getDescription() != null) p.setDescription(req.getDescription());
        if (req.getImageUrl() != null)    p.setImageUrl(req.getImageUrl());
        if (req.getBasePrice() != null)   p.setBasePrice(req.getBasePrice());
        if (req.getIsActive() != null)    p.setIsActive(req.getIsActive());
        if (req.getDisplayOrder() != null) p.setDisplayOrder(req.getDisplayOrder());
        if (req.getVatPercent() != null)  p.setVatPercent(req.getVatPercent());
        if (req.getCategoryId() != null) {
            PosCategory cat = categoryRepo.findById(req.getCategoryId())
                    .orElseThrow(() -> new RuntimeException("Category not found: " + req.getCategoryId()));
            p.setCategory(cat);
        }
        if (req.getIsShopeeFood() != null) {
            p.setIsShopeeFood(req.getIsShopeeFood());
            _upsertAppMenu(p, AppPlatform.SHOPEE_FOOD, req.getIsShopeeFood(), req.getShopeePrice());
        }
        if (req.getIsGrabFood() != null) {
            p.setIsGrabFood(req.getIsGrabFood());
            _upsertAppMenu(p, AppPlatform.GRAB_FOOD, req.getIsGrabFood(), req.getGrabPrice());
        }
        p.setUpdatedAt(System.currentTimeMillis());
        return toProductResponse(productRepo.save(p));
    }

    @Transactional
    public void deleteProduct(Long id) {
        PosProduct p = productRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));

        productRepo.delete(p);
    }

    // ════════════════════════════════════════
    // VARIANT (không cần storeId — variant thuộc product)
    // ════════════════════════════════════════

    private void _normalizeDefault(PosProduct product, PosVariant changedVariant) {
        List<PosVariant> regularVariants = variantRepo
                .findByProductAndIsActiveTrueOrderByDisplayOrderAsc(product)
                .stream().filter(v -> !Boolean.TRUE.equals(v.getIsAddonGroup()))
                .toList();
        if (regularVariants.isEmpty()) return;
        if (Boolean.TRUE.equals(changedVariant.getIsDefault())) {
            for (PosVariant v : regularVariants) {
                if (!v.getId().equals(changedVariant.getId()) && Boolean.TRUE.equals(v.getIsDefault())) {
                    v.setIsDefault(false); variantRepo.save(v);
                }
            }
        } else {
            boolean anyDefault = regularVariants.stream().anyMatch(v -> Boolean.TRUE.equals(v.getIsDefault()));
            if (!anyDefault) {
                PosVariant first = regularVariants.get(0);
                first.setIsDefault(true); variantRepo.save(first);
            }
        }
    }

    @Transactional
    public PosProductResponse createVariant(CreatePosVariantRequest req) {
        PosProduct product = productRepo.findById(req.getProductId())
                .orElseThrow(() -> new RuntimeException("Product not found: " + req.getProductId()));
        if (req.getMinSelect() > req.getMaxSelect())
            throw new IllegalArgumentException("minSelect phải <= maxSelect");
        boolean isAddon = Boolean.TRUE.equals(req.getIsAddonGroup());
        Boolean isDefault = false;
        if (!isAddon) {
            long existing = variantRepo.findByProductAndIsActiveTrueOrderByDisplayOrderAsc(product)
                    .stream().filter(v -> !Boolean.TRUE.equals(v.getIsAddonGroup())).count();
            isDefault = existing == 0 || Boolean.TRUE.equals(req.getIsDefault());
        }
        long now = System.currentTimeMillis();

        PosVariant variant = PosVariant.builder()
                .product(product).groupName(req.getGroupName())
                .minSelect(isAddon ? 0 : req.getMinSelect())
                .maxSelect(isAddon ? 999 : req.getMaxSelect())   // ← addon luôn 999
                .allowRepeat(req.getAllowRepeat() != null ? req.getAllowRepeat() : true)
                .displayOrder(req.getDisplayOrder() != null ? req.getDisplayOrder() : 0)
                .isActive(true).isAddonGroup(isAddon).isDefault(isDefault).createdAt(now).build();

        variant = variantRepo.save(variant);
        if (Boolean.TRUE.equals(isDefault)) _normalizeDefault(product, variant);
        List<PosVariantIngredient> ingredients = new ArrayList<>();
        for (var item : req.getIngredients()) {
            PosIngredient ing = ingredientRepo.findById(item.getIngredientId())
                    .orElseThrow(() -> new RuntimeException("Ingredient not found: " + item.getIngredientId()));
            ingredients.add(PosVariantIngredient.builder()
                    .variant(variant).ingredient(ing)
                    .stockDeductPerUnit(item.getStockDeductPerUnit() != null ? item.getStockDeductPerUnit() : BigDecimal.ONE)
                    .maxSelectableCount(item.getMaxSelectableCount())
                    .subGroupTag(item.getSubGroupTag()).subGroupMaxSelect(item.getSubGroupMaxSelect())
                    .displayOrder(item.getDisplayOrder() != null ? item.getDisplayOrder() : 0).build());
        }
        variantIngredientRepo.saveAll(ingredients);
        return toProductResponse(productRepo.findById(product.getId()).orElseThrow());
    }

    @Transactional
    public PosProductResponse updateVariant(Long variantId, CreatePosVariantRequest req) {
        PosVariant variant = variantRepo.findById(variantId)
                .orElseThrow(() -> new RuntimeException("Variant not found: " + variantId));
        boolean isAddon = Boolean.TRUE.equals(variant.getIsAddonGroup());

        if (req.getGroupName() != null)    variant.setGroupName(req.getGroupName());
        if (!isAddon && req.getMinSelect() != null) variant.setMinSelect(req.getMinSelect());
        if (!isAddon && req.getMaxSelect() != null) variant.setMaxSelect(req.getMaxSelect());
        // addon: luôn giữ minSelect=0, maxSelect=999
        if (isAddon) { variant.setMinSelect(0); variant.setMaxSelect(999); }
        if (req.getAllowRepeat() != null)   variant.setAllowRepeat(req.getAllowRepeat());
        if (req.getDisplayOrder() != null) variant.setDisplayOrder(req.getDisplayOrder());
        if (req.getIsAddonGroup() != null) variant.setIsAddonGroup(req.getIsAddonGroup());

        if (!isAddon && req.getIsDefault() != null) variant.setIsDefault(req.getIsDefault());
        variantRepo.save(variant);
        if (!isAddon) _normalizeDefault(variant.getProduct(), variant);
        if (req.getIngredients() != null && !req.getIngredients().isEmpty()) {
            variantIngredientRepo.deleteByVariant(variant);
            variantIngredientRepo.flush();
            List<PosVariantIngredient> newIngs = new ArrayList<>();
            for (var item : req.getIngredients()) {
                PosIngredient ing = ingredientRepo.findById(item.getIngredientId())
                        .orElseThrow(() -> new RuntimeException("Ingredient not found: " + item.getIngredientId()));
                newIngs.add(PosVariantIngredient.builder()
                        .variant(variant).ingredient(ing)
                        .stockDeductPerUnit(item.getStockDeductPerUnit() != null ? item.getStockDeductPerUnit() : BigDecimal.ONE)
                        .maxSelectableCount(item.getMaxSelectableCount())
                        .subGroupTag(item.getSubGroupTag()).subGroupMaxSelect(item.getSubGroupMaxSelect())
                        .displayOrder(item.getDisplayOrder() != null ? item.getDisplayOrder() : 0).build());
            }
            variantIngredientRepo.saveAll(newIngs);
        }
        return toProductResponse(productRepo.findById(variant.getProduct().getId()).orElseThrow());
    }

    @Transactional
    public void deleteVariant(Long variantId) {
        PosVariant v = variantRepo.findById(variantId)
                .orElseThrow(() -> new RuntimeException("Variant not found: " + variantId));
        boolean wasDefault = Boolean.TRUE.equals(v.getIsDefault()) && !Boolean.TRUE.equals(v.getIsAddonGroup());
        v.setIsActive(false); v.setIsDefault(false); variantRepo.save(v);
        if (wasDefault) {
            List<PosVariant> remaining = variantRepo
                    .findByProductAndIsActiveTrueOrderByDisplayOrderAsc(v.getProduct())
                    .stream().filter(rv -> !Boolean.TRUE.equals(rv.getIsAddonGroup()))
                    .collect(Collectors.toList());
            if (!remaining.isEmpty()) { remaining.get(0).setIsDefault(true); variantRepo.save(remaining.get(0)); }
        }
    }

    // ════════════════════════════════════════
    // APP MENU
    // ════════════════════════════════════════

    @Transactional
    public PosProductResponse createAppMenu(CreatePosAppMenuRequest req) {
        PosProduct product = productRepo.findById(req.getProductId())
                .orElseThrow(() -> new RuntimeException("Product not found: " + req.getProductId()));
        appMenuRepo.findByProductAndPlatform(product, req.getPlatform())
                .ifPresent(m -> { throw new RuntimeException("App menu đã tồn tại cho " + req.getPlatform()); });
        appMenuRepo.save(PosAppMenu.builder()
                .product(product).platform(req.getPlatform()).price(req.getPrice()).isActive(true).build());
        return toProductResponse(productRepo.findById(product.getId()).orElseThrow());
    }

    @Transactional
    public void deleteAppMenu(Long appMenuId) {
        PosAppMenu m = appMenuRepo.findById(appMenuId)
                .orElseThrow(() -> new RuntimeException("App menu not found: " + appMenuId));
        m.setIsActive(false); appMenuRepo.save(m);
    }

    // ════════════════════════════════════════
    // SHIFT — dùng storeId
    // ════════════════════════════════════════

    @Transactional
    public PosShiftResponse openShift(OpenShiftRequest req, Long userId, Long storeId) {
        // Kiểm tra store chưa có ca OPEN
        shiftRepo.findOpenShiftByStoreId(storeId, userId, ShiftStatus.OPEN)
                .ifPresent(s -> { throw new RuntimeException("Store đã có ca đang mở. Vui lòng đóng ca trước."); });

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        boolean isFirstShift = !shiftRepo.existsShiftByStoreAndDate(storeId, today);

        PosShift previousShift = null;
        if (!isFirstShift) {
            previousShift = shiftRepo.findLatestClosedShiftByStoreAndDate(storeId, today)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy ca đã đóng trong ngày."));
        }
        if (isFirstShift && (req.getOpenInventory() == null || req.getOpenInventory().isEmpty()))
            throw new RuntimeException("Ca đầu tiên trong ngày phải nhập kho nguyên liệu.");

        long now = System.currentTimeMillis();
        PosShift shift = PosShift.builder()
                .openedBy(user).staffName(req.getStaffName()).status(ShiftStatus.OPEN)
                .storeId(storeId)
                .openTime(now).shiftDate(today).isFirstShiftOfDay(isFirstShift).build();
        shift = shiftRepo.save(shift);

        BigDecimal openCash = BigDecimal.ZERO;
        List<PosShiftDenomination> denoms = new ArrayList<>();
        for (var d : req.getOpenDenominations()) {
            denoms.add(PosShiftDenomination.builder()
                    .shift(shift).denomination(d.getDenomination()).quantity(d.getQuantity()).build());
            openCash = openCash.add(BigDecimal.valueOf((long) d.getDenomination() * d.getQuantity()));
        }
        openDenomRepo.saveAll(denoms);
        shift.setOpeningCash(openCash);

        if (isFirstShift) {
            List<PosShiftOpenInventory> inv = new ArrayList<>();
            for (var item : req.getOpenInventory()) {
                PosIngredient ing = ingredientRepo.findById(item.getIngredientId())
                        .orElseThrow(() -> new RuntimeException("Ingredient not found: " + item.getIngredientId()));
                inv.add(PosShiftOpenInventory.builder()
                        .shift(shift)
                        .ingredientId(ing.getId())                    // ← snapshot
                        .ingredientName(ing.getName())                // ← snapshot
                        .unit(ing.getUnit() != null ? ing.getUnit() : "Cái") // ← snapshot
                        .packQuantity(item.getPackQuantity() != null ? item.getPackQuantity() : 0)
                        .unitQuantity(item.getUnitQuantity() != null
                                ? item.getUnitQuantity().setScale(2, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO)
                        .build());
            }
            openInvRepo.saveAll(inv);
        } else {
            final var shiftFinal = shift;
            List<PosShiftOpenInventory> inv = closeInvRepo.findByShift(previousShift).stream()
                    .map(c -> PosShiftOpenInventory.builder()
                            .shift(shiftFinal)
                            .ingredientId(c.getIngredientId())       // ← snapshot
                            .ingredientName(c.getIngredientName())   // ← snapshot
                            .unit(c.getUnit())                       // ← snapshot
                            .packQuantity(c.getPackQuantity())
                            .unitQuantity(c.getUnitQuantity())
                            .build())
                    .collect(Collectors.toList());
            openInvRepo.saveAll(inv);
        }
        shiftRepo.save(shift);
        return toShiftResponse(shiftRepo.findById(shift.getId()).orElseThrow());
    }

    @Transactional
    public PosShiftResponse closeShift(CloseShiftRequest req, Long userId, Long storeId) {
        // Chỉ user mở ca mới được đóng ca
        PosShift shift = shiftRepo.findOpenShiftByStoreIdAndUserId(storeId, userId, ShiftStatus.OPEN)
                .orElseThrow(() -> new RuntimeException("Bạn không có ca đang mở."));

        BigDecimal closeCash = BigDecimal.ZERO;
        List<PosShiftCloseDenomination> denoms = new ArrayList<>();
        for (var d : req.getCloseDenominations()) {
            denoms.add(PosShiftCloseDenomination.builder()
                    .shift(shift).denomination(d.getDenomination()).quantity(d.getQuantity()).build());
            closeCash = closeCash.add(BigDecimal.valueOf((long) d.getDenomination() * d.getQuantity()));
        }
        closeDenomRepo.saveAll(denoms);

        List<PosShiftCloseInventory> invClose = new ArrayList<>();
        for (var item : req.getCloseInventory()) {
            PosIngredient ing = ingredientRepo.findById(item.getIngredientId())
                    .orElseThrow(() -> new RuntimeException("Ingredient not found: " + item.getIngredientId()));
            invClose.add(PosShiftCloseInventory.builder()
                    .shift(shift)
                    .ingredientId(ing.getId())                    // ← snapshot
                    .ingredientName(ing.getName())                // ← snapshot
                    .unit(ing.getUnit() != null ? ing.getUnit() : "Cái") // ← snapshot
                    .packQuantity(item.getPackQuantity() != null ? item.getPackQuantity() : 0)
                    .unitQuantity(item.getUnitQuantity() != null
                            ? item.getUnitQuantity().setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO)
                    .build());
        }
        closeInvRepo.saveAll(invClose);

        shift.setStatus(ShiftStatus.CLOSED);
        shift.setCloseTime(System.currentTimeMillis());
        shift.setClosingCash(closeCash);
        shift.setTransferAmount(req.getTransferAmount() != null ? req.getTransferAmount() : BigDecimal.ZERO);
        shift.setNote(req.getNote());
        shiftRepo.save(shift);
        return toShiftResponse(shift);
    }

    public PosShiftResponse getCurrentShift(Long userId, Long storeId) {
        // Trả ca OPEN của store — bất kể ai mở
        PosShift shift = shiftRepo.findOpenShiftByStoreId(storeId, userId, ShiftStatus.OPEN)
                .orElseThrow(() -> new RuntimeException("Không có ca đang mở."));
        return toShiftResponse(shift);
    }

    public PosShiftResponse getShiftById(Long shiftId) {
        return toShiftResponse(shiftRepo.findById(shiftId)
                .orElseThrow(() -> new RuntimeException("Shift not found: " + shiftId)));
    }

    public List<PosShiftResponse> getShiftsByDate(String date, Long storeId) {
        List<PosShift> shifts = shiftRepo.findShiftsByStoreAndDate(storeId, date);
        return shifts.stream()
                .map(this::toShiftResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public PosOrderResponse createOrder(CreatePosOrderRequest req, Long userId, Long storeId) {
        PosShift shift = shiftRepo.findOpenShiftByStoreId(storeId, userId, ShiftStatus.OPEN)
                .orElseThrow(() -> new RuntimeException("Chưa mở ca. Vui lòng mở ca trước khi tạo đơn."));

        User user = userRepo.findById(userId).orElseThrow();
        long now = System.currentTimeMillis();
        String orderCode = generateOrderCode();

        BigDecimal totalAmount = BigDecimal.ZERO;   // Tổng raw (trước rate, trước discount)
        BigDecimal finalAmount = BigDecimal.ZERO;   // Tổng net (sau rate, sau discount)
        BigDecimal totalVat    = BigDecimal.ZERO;

        boolean isAppOrder = req.getOrderSource() == OrderSource.SHOPEE_FOOD
                || req.getOrderSource() == OrderSource.GRAB_FOOD;

        // ── Lấy platform rate TRƯỚC vòng lặp ─────────────────────
        BigDecimal platformRate = BigDecimal.ZERO;
        AppPlatform platform = null;
        if (isAppOrder) {
            platform = req.getOrderSource() == OrderSource.SHOPEE_FOOD
                    ? AppPlatform.SHOPEE_FOOD : AppPlatform.GRAB_FOOD;
            PosStore storeForRate = posStoreRepo.findById(storeId).orElse(null);
            if (storeForRate != null) {
                platformRate = platform == AppPlatform.SHOPEE_FOOD
                        ? storeForRate.getShopeeRate()
                        : storeForRate.getGrabRate();
                if (platformRate == null) platformRate = BigDecimal.ZERO;
            }
        }
        final AppPlatform platformFinal = platform;

        record CalcItem(
                PosProduct product,
                BigDecimal basePrice,           // giá user nhập (raw, trước rate)
                int discountPercent,
                BigDecimal finalUnitPrice,      // (basePrice - discount/qty) × (1-rate) [net]
                int quantity,
                BigDecimal subtotal,            // finalUnitPrice×qty + addonNet
                int vatPercent,
                BigDecimal vatAmount,
                BigDecimal addonAmount,         // addon net (đã × (1-rate) nếu app)
                BigDecimal addonRaw,            // addon trước rate (để tính totalAmount)
                List<CreatePosOrderRequest.OrderItemRequest.VariantSelection> variantSelections,
                String note,
                String categoryName,
                BigDecimal requestedFinalUnitPrice
        ) {}

        List<CalcItem> calcs = new ArrayList<>();

        for (var itemReq : req.getItems()) {
            PosProduct product = productRepo.findById(itemReq.getProductId())
                    .orElseThrow(() -> new RuntimeException("Product not found: " + itemReq.getProductId()));

            if (!storeId.equals(product.getStoreId()))
                throw new IllegalArgumentException("Sản phẩm '" + product.getName() + "' không thuộc store của bạn.");

            BigDecimal basePrice;
            int discountPercent = 0;

            if (isAppOrder) {
                PosAppMenu appMenu = appMenuRepo.findByProductAndPlatform(product, platformFinal)
                        .orElseThrow(() -> new RuntimeException(
                                "App menu chưa thiết lập cho " + product.getName() + " trên " + platformFinal));

                // basePrice = giá user nhập (giá bán trên app, raw trước discount & rate)
                basePrice = (itemReq.getFinalUnitPrice() != null
                        && itemReq.getFinalUnitPrice().compareTo(BigDecimal.ZERO) > 0)
                        ? itemReq.getFinalUnitPrice()
                        : appMenu.getPrice();

            } else {
                // ==================== ĐƠN OFFLINE ====================
                discountPercent = itemReq.getDiscountPercent() != null ? itemReq.getDiscountPercent() : 0;

                boolean singlePrice = Boolean.TRUE.equals(product.getCategory().getSinglePrice());
                if (singlePrice && discountPercent != 0)
                    throw new IllegalArgumentException("Sản phẩm category Lạnh chỉ có 1 giá.");

                if (!List.of(0, 10, 20, 100).contains(discountPercent))
                    throw new IllegalArgumentException("discountPercent phải là 0/10/20/100");

                // basePrice luôn là giá gốc product — Flutter gửi finalUnitPrice đã tính sẵn
                // nhưng chúng ta không dùng nó làm basePrice
                basePrice = product.getBasePrice();
            }

            // ── Tính addon RAW (trước rate) ──────────────────────
            BigDecimal addonRaw = BigDecimal.ZERO;
            if (itemReq.getVariantSelections() != null) {
                for (var sel : itemReq.getVariantSelections()) {
                    if (!Boolean.TRUE.equals(sel.getIsAddonGroup())) continue;
                    for (var s : sel.getSelectedIngredients()) {
                        if (s.getAddonPriceSnapshot() != null) {
                            addonRaw = addonRaw.add(
                                    s.getAddonPriceSnapshot()
                                            .multiply(BigDecimal.valueOf(s.getSelectedCount())));
                        }
                    }
                }
            }
            BigDecimal addonRawTotal = addonRaw.multiply(BigDecimal.valueOf(itemReq.getQuantity()));

            // ── totalAmount luôn dùng giá RAW ────────────────────
            BigDecimal effectivePrice = (!isAppOrder
                    && itemReq.getFinalUnitPrice() != null
                    && itemReq.getFinalUnitPrice().compareTo(BigDecimal.ZERO) > 0)
                    ? itemReq.getFinalUnitPrice()
                    : basePrice;

            BigDecimal itemBaseTotal = effectivePrice.multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            totalAmount = totalAmount.add(itemBaseTotal).add(addonRawTotal);

            // VAT tính trên basePrice raw (sẽ điều chỉnh nếu cần)
            int vatPct = product.getVatPercent() != null ? product.getVatPercent() : 0;

            // VAT đã bao gồm trong giá → tính ngược
            // vatAmount = baseTotal × rate / (1 + rate)
            BigDecimal vatAmount = BigDecimal.ZERO;
            if (vatPct > 0) {
                BigDecimal rate = BigDecimal.valueOf(vatPct)
                        .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                vatAmount = itemBaseTotal.add(addonRawTotal)
                        .multiply(rate)
                        .divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
            }
            totalVat = totalVat.add(vatAmount);

            String categoryName = product.getCategory() != null ? product.getCategory().getName() : null;

            // Validate variant selections
            if (itemReq.getVariantSelections() != null && !itemReq.getVariantSelections().isEmpty()) {
                for (var sel : itemReq.getVariantSelections()) {
                    PosVariant variant = variantRepo.findById(sel.getVariantId())
                            .orElseThrow(() -> new RuntimeException("Variant not found: " + sel.getVariantId()));

                    if (!variant.getProduct().getId().equals(product.getId()))
                        throw new IllegalArgumentException("Variant không thuộc sản phẩm này.");

                    boolean isAddon = Boolean.TRUE.equals(variant.getIsAddonGroup());

                    if (!isAddon) {
                        int totalSelected = sel.getSelectedIngredients().stream()
                                .mapToInt(CreatePosOrderRequest.OrderItemRequest.SelectedIngredient::getSelectedCount).sum();
                        if (totalSelected < variant.getMinSelect() || totalSelected > variant.getMaxSelect())
                            throw new IllegalArgumentException(String.format(
                                    "Nhóm '%s': phải chọn %d-%d, hiện tại %d",
                                    variant.getGroupName(), variant.getMinSelect(), variant.getMaxSelect(), totalSelected));
                    }

                    Map<Long, PosVariantIngredient> viMap = variantIngredientRepo.findByVariant(variant)
                            .stream().collect(Collectors.toMap(vi -> vi.getIngredient().getId(), vi -> vi));

                    boolean allowRepeat = Boolean.TRUE.equals(variant.getAllowRepeat());
                    for (var s : sel.getSelectedIngredients()) {
                        PosVariantIngredient vi = viMap.get(s.getIngredientId());
                        if (vi == null)
                            throw new IllegalArgumentException("Nguyên liệu #" + s.getIngredientId() + " không tồn tại trong nhóm.");
                        if (!isAddon && !allowRepeat && s.getSelectedCount() > 1)
                            throw new IllegalArgumentException("Nguyên liệu chỉ được chọn 1 lần.");
                        if (!isAddon && !allowRepeat && vi.getMaxSelectableCount() != null
                                && s.getSelectedCount() > vi.getMaxSelectableCount())
                            throw new IllegalArgumentException(String.format(
                                    "'%s' tối đa %d lần.", vi.getIngredient().getName(), vi.getMaxSelectableCount()));
                    }

                    if (!isAddon) {
                        Map<String, Integer> subTotals = new HashMap<>(), subLimits = new HashMap<>();
                        for (var s : sel.getSelectedIngredients()) {
                            PosVariantIngredient vi = viMap.get(s.getIngredientId());
                            if (vi != null && vi.getSubGroupTag() != null) {
                                subTotals.merge(vi.getSubGroupTag(), s.getSelectedCount(), Integer::sum);
                                subLimits.putIfAbsent(vi.getSubGroupTag(), vi.getSubGroupMaxSelect());
                            }
                        }
                        for (var entry : subTotals.entrySet()) {
                            Integer limit = subLimits.get(entry.getKey());
                            if (limit != null && entry.getValue() > limit)
                                throw new IllegalArgumentException(String.format(
                                        "Nhóm phụ '%s': tối đa %d.", entry.getKey(), limit));
                        }
                    }
                }
            }

            // Tạm thời thêm vào calcs với finalUnitPrice = basePrice (sẽ recalc sau)
            calcs.add(new CalcItem(
                    product, basePrice, discountPercent,
                    basePrice,
                    itemReq.getQuantity(),
                    itemBaseTotal.add(addonRawTotal),
                    vatPct, vatAmount,
                    addonRawTotal, addonRawTotal,
                    itemReq.getVariantSelections(), itemReq.getNote(), categoryName,
                    itemReq.getFinalUnitPrice()  // ← THÊM
            ));
        }

        // ════════════════════════════════════════════════════════
        // XỬ LÝ APP DISCOUNT & TÍNH NET PRICES
        // ════════════════════════════════════════════════════════
        BigDecimal appDiscountAmount = BigDecimal.ZERO;

        if (isAppOrder) {
            // Lấy discount amount từ request
            if (req.getAppDiscountAmount() != null
                    && req.getAppDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
                appDiscountAmount = req.getAppDiscountAmount();
            } else if (req.getAppFinalAmount() != null
                    && req.getAppFinalAmount().compareTo(BigDecimal.ZERO) > 0) {
                // user nhập giá net cuối → tính ngược ra discount raw
                BigDecimal grossFromFinal = req.getAppFinalAmount()
                        .divide(BigDecimal.ONE.subtract(platformRate), 10, RoundingMode.HALF_UP);
                appDiscountAmount = totalAmount.subtract(grossFromFinal).max(BigDecimal.ZERO);
            }

            // finalAmount = (totalAmount - discountRaw) × (1 - rate)
            finalAmount = totalAmount
                    .subtract(appDiscountAmount)
                    .multiply(BigDecimal.ONE.subtract(platformRate))
                    .setScale(0, RoundingMode.FLOOR);

            // ── Recalculate finalUnitPrice và addonAmount net cho từng item ──
            BigDecimal totalBaseOnly = calcs.stream()
                    .map(c -> c.basePrice().multiply(BigDecimal.valueOf(c.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal discountForBase = appDiscountAmount.min(totalBaseOnly);

            List<CalcItem> rebuiltCalcs = new ArrayList<>();
            for (CalcItem calc : calcs) {
                BigDecimal itemBaseTotal2 = calc.basePrice().multiply(BigDecimal.valueOf(calc.quantity()));

                BigDecimal itemDiscount = totalBaseOnly.compareTo(BigDecimal.ZERO) > 0
                        ? discountForBase
                        .multiply(itemBaseTotal2)
                        .divide(totalBaseOnly, 10, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

                BigDecimal discountPerUnit = BigDecimal.valueOf(calc.quantity()) .compareTo(BigDecimal.ZERO) > 0
                        ? itemDiscount.divide(BigDecimal.valueOf(calc.quantity()), 10, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

                BigDecimal newFinalUnitPrice = calc.basePrice()
                        .subtract(discountPerUnit)
                        .multiply(BigDecimal.ONE.subtract(platformRate))
                        .setScale(0, RoundingMode.CEILING);  // ← CEILING thay vì HALF_UP

                BigDecimal newAddonNet = calc.addonRaw()
                        .multiply(BigDecimal.ONE.subtract(platformRate))
                        .setScale(2, RoundingMode.HALF_UP);

                BigDecimal newSubtotal = newFinalUnitPrice
                        .multiply(BigDecimal.valueOf(calc.quantity()))
                        .add(newAddonNet);

                rebuiltCalcs.add(new CalcItem(
                        calc.product(), calc.basePrice(), calc.discountPercent(),
                        newFinalUnitPrice, calc.quantity(), newSubtotal,
                        calc.vatPercent(), calc.vatAmount(), newAddonNet, calc.addonRaw(),
                        calc.variantSelections(), calc.note(), calc.categoryName(),
                        calc.requestedFinalUnitPrice()  // ← THÊM
                ));
            }
            calcs = rebuiltCalcs;

        } else {
            // ==================== OFFLINE: giá như cũ ====================
            // Rebuild calcs với finalUnitPrice đúng cho offline
            List<CalcItem> rebuiltOffline = new ArrayList<>();
            for (CalcItem calc : calcs) {
                BigDecimal finalUnitPrice;

                if (calc.requestedFinalUnitPrice() != null
                        && calc.requestedFinalUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
                    // Flutter đã tính sẵn (weight × discounted price)
                    finalUnitPrice = calc.requestedFinalUnitPrice();
                } else {
                    // Không có weight override, tính từ basePrice × (1 - discount%)
                    finalUnitPrice = roundToThousand(
                            calc.basePrice().multiply(BigDecimal.ONE.subtract(
                                    BigDecimal.valueOf(calc.discountPercent())
                                            .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP))));
                }

                BigDecimal newSubtotal = finalUnitPrice
                        .multiply(BigDecimal.valueOf(calc.quantity()))
                        .add(calc.addonRaw());
                finalAmount = finalAmount.add(newSubtotal);

                rebuiltOffline.add(new CalcItem(
                        calc.product(), calc.basePrice(), calc.discountPercent(),
                        finalUnitPrice, calc.quantity(), newSubtotal,
                        calc.vatPercent(), calc.vatAmount(), calc.addonRaw(), calc.addonRaw(),
                        calc.variantSelections(), calc.note(), calc.categoryName(),
                        calc.requestedFinalUnitPrice()));
            }
            calcs = rebuiltOffline;
        }

        // ==================== CUSTOMER DISCOUNT ====================
        BigDecimal customerDiscountAmt = BigDecimal.ZERO;
        String discountNote = null;

        if (req.getCustomerDiscountId() != null) {
            PosCustomerDiscount customerDiscount = customerDiscountRepo
                    .findById(req.getCustomerDiscountId()).orElse(null);

            if (customerDiscount != null) {
                BigDecimal selectedItemPrice = null;
                if (customerDiscount.getSelectedOption() != null
                        && customerDiscount.getSelectedOption().isItemType()
                        && req.getDiscountItemProductId() != null) {

                    selectedItemPrice = calcs.stream()
                            .filter(c -> c.product().getId().equals(req.getDiscountItemProductId()))
                            .findFirst()
                            .map(c -> c.finalUnitPrice().multiply(BigDecimal.valueOf(c.quantity())))
                            .orElse(BigDecimal.ZERO);
                }

                PosDiscountService.DiscountResult dr =
                        posDiscountService.calculate(customerDiscount, finalAmount, selectedItemPrice);

                customerDiscountAmt = dr.discountAmount();
                discountNote = dr.note();
            }
        }

        finalAmount = finalAmount
                .subtract(customerDiscountAmt)
                .max(BigDecimal.ZERO);

        if (!isAppOrder
                && req.getManualDiscountAmount() != null
                && req.getManualDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal manualDiscount = req.getManualDiscountAmount().min(finalAmount);
            finalAmount = finalAmount.subtract(manualDiscount).max(BigDecimal.ZERO);
            // Làm tròn lên nghìn
            BigDecimal remainder = finalAmount.remainder(BigDecimal.valueOf(1000));
            if (remainder.compareTo(BigDecimal.ZERO) != 0) {
                finalAmount = finalAmount
                        .subtract(remainder)
                        .add(BigDecimal.valueOf(1000));
            }
        }

        // ==================== TÍNH LẠI VAT SAU DISCOUNT ====================
        BigDecimal manualDiscountAmt = (!isAppOrder
                && req.getManualDiscountAmount() != null
                && req.getManualDiscountAmount().compareTo(BigDecimal.ZERO) > 0)
                ? req.getManualDiscountAmount()
                : BigDecimal.ZERO;

        BigDecimal totalDiscountForVat = isAppOrder
                ? appDiscountAmount
                : manualDiscountAmt.add(customerDiscountAmt);

        totalVat = BigDecimal.ZERO;
        List<CalcItem> rebuiltWithVat = new ArrayList<>();
        for (CalcItem calc : calcs) {
            BigDecimal newVatAmount = BigDecimal.ZERO;
            BigDecimal newFinalUnitPrice = calc.finalUnitPrice(); // giữ nguyên cho app order

            if (calc.vatPercent() > 0 && totalAmount.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal itemDiscount = totalDiscountForVat
                        .multiply(calc.subtotal())
                        .divide(totalAmount, 10, RoundingMode.HALF_UP);
                BigDecimal itemAfterDiscount = calc.subtotal()
                        .subtract(itemDiscount)
                        .max(BigDecimal.ZERO);
                BigDecimal rate = BigDecimal.valueOf(calc.vatPercent())
                        .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                newVatAmount = itemAfterDiscount
                        .multiply(rate)
                        .divide(BigDecimal.ONE.add(rate), 0, RoundingMode.HALF_UP);

                // ← XÓA block: if (!isAppOrder && calc.quantity() > 0) { newFinalUnitPrice = ... }

            } else if (!isAppOrder
                    && calc.vatPercent() == 0
                    && totalAmount.compareTo(BigDecimal.ZERO) > 0) {
                // ← XÓA block này luôn
            }

            if (!isAppOrder) {
                newFinalUnitPrice = calc.requestedFinalUnitPrice() != null
                        && calc.requestedFinalUnitPrice().compareTo(BigDecimal.ZERO) > 0
                        ? calc.requestedFinalUnitPrice()
                        : calc.finalUnitPrice();
            }

            totalVat = totalVat.add(newVatAmount);
            rebuiltWithVat.add(new CalcItem(
                    calc.product(), calc.basePrice(), calc.discountPercent(),
                    newFinalUnitPrice, calc.quantity(), calc.subtotal(),
                    calc.vatPercent(), newVatAmount,
                    calc.addonAmount(), calc.addonRaw(),
                    calc.variantSelections(), calc.note(), calc.categoryName(),
                    calc.requestedFinalUnitPrice()  // ← THÊM
            ));
        }
        calcs = rebuiltWithVat;

        // ==================== TẠO ORDER ====================
        PosStore store = posStoreRepo.findById(storeId)
                .orElseThrow(() -> new RuntimeException("Store not found: " + storeId));

        BigDecimal grossAmount = totalAmount.subtract(appDiscountAmount); // (total - discount) raw
        BigDecimal platformFeeAmountSnapshot = isAppOrder
                ? totalAmount.subtract(appDiscountAmount).subtract(finalAmount)
                : BigDecimal.ZERO;

        // Thành:
        BigDecimal totalDiscountAmount;

        if (!isAppOrder) {
            // Tính tổng discount từ % giảm giá của từng item
            BigDecimal itemDiscountTotal = calcs.stream()
                    .map(c -> {
                        if (c.discountPercent() > 0 && c.requestedFinalUnitPrice() != null) {
                            // giá chưa giảm % = finalUnitPrice / (1 - discount%)
                            BigDecimal priceBeforeDiscount = c.requestedFinalUnitPrice()
                                    .divide(BigDecimal.ONE.subtract(
                                            BigDecimal.valueOf(c.discountPercent())
                                                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                                    ), 0, RoundingMode.HALF_UP);
                            return priceBeforeDiscount.subtract(c.requestedFinalUnitPrice());
                        } else if (c.discountPercent() > 0) {
                            return roundToThousand(c.product().getBasePrice()
                                    .multiply(BigDecimal.valueOf(c.discountPercent())
                                            .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)))
                                    .multiply(BigDecimal.valueOf(c.quantity()));
                        }
                        return BigDecimal.ZERO;
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal manualDiscount = req.getManualDiscountAmount() != null
                    ? req.getManualDiscountAmount() : BigDecimal.ZERO;

            totalDiscountAmount = itemDiscountTotal.add(manualDiscount);
        } else {
            totalDiscountAmount = appDiscountAmount.add(customerDiscountAmt);
        }

        PosOrder order = PosOrder.builder()
                .orderCode(orderCode)
                .shift(shift)
                .createdBy(user)
                .store(store)
                .orderSource(req.getOrderSource())
                .status(PosOrderStatus.COMPLETED)
                .note(req.getNote())
                .totalAmount(totalAmount)                                   // raw
                .discountAmount(totalDiscountAmount) // raw discount
                .totalVatAmount(totalVat)
                .discountNote(discountNote)
                .finalAmount(finalAmount)                                   // net sau rate
                // ── Snapshot platform fee ───────────────────────────────
                .platformRate(platformRate)                                 // 0.3305
                .platformFeeAmount(platformFeeAmountSnapshot)              // (total-discount)×rate
                // ───────────────────────────────────────────────────────
                .customerPhone(req.getCustomerPhone())
                .customerName(req.getCustomerName())
                .paymentMethod(req.getPaymentMethod())
                .createdAt(now)
                .updatedAt(now)
                .build();

        order = orderRepo.save(order);


        // Commit customer discount
        if (req.getCustomerDiscountId() != null) {
            PosCustomerDiscount cd = customerDiscountRepo.findById(req.getCustomerDiscountId()).orElse(null);
            if (cd != null) posDiscountService.commitDiscount(cd, customerDiscountAmt);
        }

        // Cập nhật tổng chi tiêu khách hàng
        if (req.getCustomerPhone() != null && !req.getCustomerPhone().isBlank()) {
            String normalizedPhone = PosCustomerService.normalizePhone(req.getCustomerPhone());
            PosCustomer customer = posCustomerRepo.findByPhone(normalizedPhone).orElse(null);
            if (customer != null) {
                customer.setTotalSpend(customer.getTotalSpend().add(finalAmount));
                posCustomerRepo.save(customer);
            }
        }

        // ==================== LƯU ORDER ITEMS + INGREDIENTS ====================
        for (CalcItem calc : calcs) {
            BigDecimal defaultPrice;
            BigDecimal basePrice = calc.basePrice();
            int discountPercent = 0;
            if (isAppOrder) {
                discountPercent = calc.discountPercent();
                defaultPrice = appMenuRepo
                        .findByProductAndPlatform(calc.product(), platformFinal)
                        .map(PosAppMenu::getPrice)
                        .orElse(calc.basePrice);
            } else {
                defaultPrice = calc.product.getBasePrice();
                // basePrice = giá sau giảm % (làm tròn lên nghìn cho offline)
                if (calc.discountPercent() > 0) {
                    basePrice = roundToThousand(
                            calc.product.getBasePrice()
                                    .multiply(BigDecimal.ONE.subtract(
                                            BigDecimal.valueOf(calc.discountPercent())
                                                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                                    ))
                    );
                    discountPercent = calc.discountPercent();
                } else {
                    basePrice = calc.product.getBasePrice();
                }
            }

            PosOrderItem orderItem = PosOrderItem.builder()
                    .order(order)
                    .productId(calc.product().getId())
                    .productName(calc.product().getName())
                    .productImageUrl(calc.product().getImageUrl())
                    .categoryName(calc.categoryName())
                    .discountPercent(discountPercent)
                    .quantity(calc.quantity())
                    .basePrice(basePrice)
                    .defaultPrice(defaultPrice)
                    .finalUnitPrice(calc.finalUnitPrice())
                    .subtotal(calc.subtotal())
                    .vatPercent(calc.vatPercent())
                    .vatAmount(calc.vatAmount())
                    .addonAmount(calc.addonAmount())
                    .note(calc.note())
                    .build();

            orderItem = orderItemRepo.save(orderItem);

            // ... phần lưu ingredients giữ nguyên ...
            if (calc.variantSelections() != null && !calc.variantSelections().isEmpty()) {
                List<PosOrderItemIngredient> ingList = new ArrayList<>();
                for (var sel : calc.variantSelections()) {
                    PosVariant variant = variantRepo.findById(sel.getVariantId()).orElseThrow();
                    Map<Long, PosVariantIngredient> viMap = variantIngredientRepo.findByVariant(variant)
                            .stream().collect(Collectors.toMap(
                                    vi -> vi.getIngredient().getId(), vi -> vi));
                    for (var s : sel.getSelectedIngredients()) {
                        PosIngredient ing = ingredientRepo.findById(s.getIngredientId()).orElseThrow();
                        PosVariantIngredient vi = viMap.get(ing.getId());
                        BigDecimal defaultDeductPerUnit = (vi != null && vi.getStockDeductPerUnit() != null)
                                ? vi.getStockDeductPerUnit() : BigDecimal.ONE;
                        List<BigDecimal> unitWeights = s.getUnitWeights();
                        BigDecimal quantityUsed = (unitWeights != null && !unitWeights.isEmpty())
                                ? unitWeights.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                                : defaultDeductPerUnit
                                .multiply(BigDecimal.valueOf(s.getSelectedCount()))
                                .multiply(BigDecimal.valueOf(calc.quantity()));

                        ingList.add(PosOrderItemIngredient.builder()
                                .orderItem(orderItem)
                                .ingredientId(ing.getId())
                                .ingredientName(ing.getName())
                                .ingredientImageUrl(ing.getImageUrl())
                                .selectedCount(s.getSelectedCount())
                                .defaultDeductPerUnit(defaultDeductPerUnit)
                                .unitWeights(unitWeights)
                                .quantityUsed(quantityUsed)
                                .variantId(variant.getId())
                                .addonPriceSnapshot(s.getAddonPriceSnapshot())
                                .variantGroupName(variant.getGroupName())
                                .build());
                    }
                }
                orderItemIngredientRepo.saveAll(ingList);
            }
        }

        return toOrderResponse(orderRepo.findById(order.getId()).orElseThrow());
    }

    private BigDecimal roundToThousand(BigDecimal price) {
        if (price == null) return BigDecimal.ZERO;

        BigDecimal remainder = price.remainder(BigDecimal.valueOf(1000));
        if (remainder.compareTo(BigDecimal.valueOf(500)) >= 0) {
            return price.subtract(remainder).add(BigDecimal.valueOf(1000));
        } else {
            return price.subtract(remainder);
        }
    }

    @Transactional
    public PosOrderResponse cancelOrder(Long orderId, String password) {
        final String CANCEL_PASSWORD = "123456";
        if (!CANCEL_PASSWORD.equals(password))
            throw new RuntimeException("Mật khẩu hủy đơn không đúng.");
        PosOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Đơn hàng không tìm thấy: " + orderId));
        if (order.getStatus() == PosOrderStatus.CANCELLED)
            throw new RuntimeException("Đơn hàng đã bị hủy trước đó.");
        order.setStatus(PosOrderStatus.CANCELLED);
        order.setUpdatedAt(System.currentTimeMillis());
        orderRepo.save(order);
        return toOrderResponse(orderRepo.findById(orderId).orElseThrow());
    }

    @Transactional
    public PosOrderResponse updateOrderPaymentMethod(Long orderId, String newPaymentMethod) {
        PosOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng #" + orderId));
        if (order.getStatus() == PosOrderStatus.CANCELLED)
            throw new IllegalArgumentException("Đơn hàng đã hủy.");
        if (!List.of("CASH", "TRANSFER").contains(newPaymentMethod))
            throw new IllegalArgumentException("paymentMethod phải là CASH hoặc TRANSFER");
        order.setPaymentMethod(newPaymentMethod);
        order.setUpdatedAt(System.currentTimeMillis());
        return toOrderResponse(orderRepo.save(order));
    }

    public PosOrderResponse getOrderById(Long orderId) {
        return toOrderResponse(orderRepo.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId)));
    }

    public List<PosOrderResponse> getOrdersByShift(Long shiftId) {
        PosShift shift = shiftRepo.findById(shiftId)
                .orElseThrow(() -> new RuntimeException("Shift not found: " + shiftId));
        return orderRepo.findByShiftOrderByCreatedAtDesc(shift)
                .stream().map(this::toOrderResponse).collect(Collectors.toList());
    }

    public Map<String, Object> getShiftReport(Long shiftId) {
        PosShift shift = shiftRepo.findById(shiftId)
                .orElseThrow(() -> new RuntimeException("Shift not found: " + shiftId));
        List<PosOrder> orders = orderRepo.findByShiftOrderByCreatedAtDesc(shift);
        BigDecimal totalRevenue = orders.stream()
                .filter(o -> o.getStatus() != PosOrderStatus.CANCELLED)
                .map(PosOrder::getFinalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalVat = orders.stream()
                .filter(o -> o.getStatus() != PosOrderStatus.CANCELLED)
                .flatMap(o -> orderItemRepo.findByOrder(o).stream())
                .map(item -> item.getVatAmount() != null ? item.getVatAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("shiftId", shiftId);
        report.put("staffName", shift.getStaffName());
        report.put("shiftDate", shift.getShiftDate());
        report.put("openTime", shift.getOpenTime());
        report.put("closeTime", shift.getCloseTime());
        report.put("status", shift.getStatus());
        report.put("totalOrders", orders.size());
        report.put("completedOrders", orders.stream().filter(o -> o.getStatus() == PosOrderStatus.COMPLETED).count());
        report.put("cancelledOrders", orders.stream().filter(o -> o.getStatus() == PosOrderStatus.CANCELLED).count());
        report.put("totalRevenue", totalRevenue);
        report.put("totalVat", totalVat);
        report.put("openingCash", shift.getOpeningCash());
        report.put("closingCash", shift.getClosingCash());
        report.put("transferAmount", shift.getTransferAmount());
        return report;
    }

    public List<StockImportResponse> getStockImportsByShift(Long shiftId) {
        PosShift shift = shiftRepo.findById(shiftId)
                .orElseThrow(() -> new RuntimeException("Shift not found: " + shiftId));
        return stockImportRepo.findByShift(shift).stream()
                .map(this::toStockImportResponse).collect(Collectors.toList());
    }

    // ════════════════════════════════════════
    // HELPERS - MAPPERS (giữ nguyên từ version cũ)
    // ════════════════════════════════════════

    private String generateOrderCode() {
        String date   = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "POS-" + date + "-";

        // Lấy số thứ tự lớn nhất hiện có (kể cả đơn đã xóa không ảnh hưởng vì dùng MAX)
        // Nếu chưa có đơn nào trong ngày → trả về "POS-20260317-0001"
        String maxCode = orderRepo.findMaxOrderCodeByPrefix(prefix).orElse(null);

        long next;
        if (maxCode == null) {
            next = 1L;
        } else {
            // maxCode = "POS-20260317-0013" → lấy "0013" → parse → 13 → next = 14
            String seq = maxCode.substring(prefix.length());
            next = Long.parseLong(seq) + 1L;
        }

        return String.format("POS-%s-%04d", date, next);
    }

    private PosCategoryResponse toCategoryResponse(PosCategory c) {
        return PosCategoryResponse.builder()
                .id(c.getId()).name(c.getName()).imageUrl(c.getImageUrl())
                .displayOrder(c.getDisplayOrder()).isActive(c.getIsActive())
                .singlePrice(c.getSinglePrice())
                .productCount(c.getProducts() != null ? c.getProducts().size() : 0).build();
    }

    private PosIngredientResponse toIngredientResponse(PosIngredient i) {
        return PosIngredientResponse.builder()
                .id(i.getId()).name(i.getName()).imageUrl(i.getImageUrl())
                .unitPerPack(i.getUnitPerPack()).isActive(i.getIsActive())
                .displayOrder(i.getDisplayOrder() != null ? i.getDisplayOrder() : 0)
                .ingredientType(i.getIngredientType() != null ? i.getIngredientType() : IngredientType.MAIN)
                .addonPrice(i.getAddonPrice() != null ? i.getAddonPrice() : BigDecimal.ZERO)
                .unit(i.getUnit() != null ? i.getUnit() : "Cái")   // ← THÊM
                .build();
    }

    private PosProductResponse toProductResponse(PosProduct p) {
        boolean singlePrice = p.getCategory() != null
                && Boolean.TRUE.equals(p.getCategory().getSinglePrice());

        List<PosProductResponse.PriceOption> priceOptions = new ArrayList<>();
        priceOptions.add(new PosProductResponse.PriceOption(0, p.getBasePrice(), "Giá gốc"));
        if (!singlePrice) {
            priceOptions.add(new PosProductResponse.PriceOption(10,
                    p.getBasePrice().multiply(BigDecimal.valueOf(0.9)).setScale(2, RoundingMode.HALF_UP), "Giảm 10%"));
            priceOptions.add(new PosProductResponse.PriceOption(20,
                    p.getBasePrice().multiply(BigDecimal.valueOf(0.8)).setScale(2, RoundingMode.HALF_UP), "Giảm 20%"));
            priceOptions.add(new PosProductResponse.PriceOption(100, BigDecimal.ZERO, "Miễn phí"));
        }
        List<PosVariant> allVariants = p.getVariants() == null ? Collections.emptyList()
                : p.getVariants().stream().filter(v -> Boolean.TRUE.equals(v.getIsActive())).toList();
        List<PosVariant> regularVariants = allVariants.stream()
                .filter(v -> !Boolean.TRUE.equals(v.getIsAddonGroup()))
                .sorted(Comparator.comparingInt(v -> (v.getDisplayOrder() != null ? v.getDisplayOrder() : 0)))
                .toList();
        List<PosVariant> addonVariants = allVariants.stream()
                .filter(v -> Boolean.TRUE.equals(v.getIsAddonGroup()))
                .sorted(Comparator.comparingInt(v -> (v.getDisplayOrder() != null ? v.getDisplayOrder() : 0)))
                .toList();
        List<PosVariant> sortedVariants = new ArrayList<>(regularVariants);
        sortedVariants.addAll(addonVariants);
        List<PosVariantResponse> variants = sortedVariants.stream().map(v ->
                PosVariantResponse.builder()
                        .id(v.getId()).groupName(v.getGroupName())
                        .minSelect(v.getMinSelect()).maxSelect(v.getMaxSelect())
                        .allowRepeat(v.getAllowRepeat()).displayOrder(v.getDisplayOrder())
                        .isActive(v.getIsActive())
                        .isAddonGroup(Boolean.TRUE.equals(v.getIsAddonGroup()))
                        .isDefault(Boolean.TRUE.equals(v.getIsDefault()))
                        .ingredients(v.getVariantIngredients() == null ? Collections.emptyList()
                                : v.getVariantIngredients().stream().map(vi ->
                                        PosVariantIngredientResponse.builder()
                                                .id(vi.getId()).ingredientId(vi.getIngredient().getId())
                                                .ingredientName(vi.getIngredient().getName())
                                                .ingredientImageUrl(vi.getIngredient().getImageUrl())
                                                .stockDeductPerUnit(vi.getStockDeductPerUnit())
                                                .maxSelectableCount(vi.getMaxSelectableCount())
                                                .subGroupTag(vi.getSubGroupTag()).subGroupMaxSelect(vi.getSubGroupMaxSelect())
                                                .displayOrder(vi.getDisplayOrder())
                                                .addonPrice(vi.getIngredient().getAddonPrice() != null
                                                        ? vi.getIngredient().getAddonPrice() : BigDecimal.ZERO).build())
                                .collect(Collectors.toList()))
                        .build()).collect(Collectors.toList());
        List<PosAppMenuResponse> appMenus = p.getAppMenus() == null ? Collections.emptyList()
                : p.getAppMenus().stream().filter(m -> Boolean.TRUE.equals(m.getIsActive()))
                .map(m -> PosAppMenuResponse.builder().id(m.getId()).platform(m.getPlatform())
                        .price(m.getPrice()).isActive(m.getIsActive()).build())
                .collect(Collectors.toList());
        return PosProductResponse.builder()
                .id(p.getId()).name(p.getName())
                .description(p.getDescription())
                .imageUrl(p.getImageUrl()).isActive(p.getIsActive())
                .categoryId(p.getCategory() != null ? p.getCategory().getId() : -1)
                .categoryName(p.getCategory() != null ? p.getCategory().getName() : "Mặc định")
                .singlePrice(singlePrice).basePrice(p.getBasePrice())
                .displayOrder(p.getDisplayOrder() != null ? p.getDisplayOrder() : 0)
                .vatPercent(p.getVatPercent() != null ? p.getVatPercent() : 0)
                .isShopeeFood(Boolean.TRUE.equals(p.getIsShopeeFood()))
                .isGrabFood(Boolean.TRUE.equals(p.getIsGrabFood()))
                .priceOptions(priceOptions).variants(variants).hasVariants(!variants.isEmpty())
                .appMenus(appMenus).build();
    }

    private PosShiftResponse toShiftResponse(PosShift s) {
        // Open Denominations
        List<PosShiftDenominationResponse> openDenoms = openDenomRepo.findByShift(s).stream()
                .map(d -> PosShiftDenominationResponse.builder()
                        .denomination(d.getDenomination())
                        .quantity(d.getQuantity())
                        .total((long) d.getDenomination() * d.getQuantity())
                        .build())
                .collect(Collectors.toList());

        // Close Denominations
        List<PosShiftDenominationResponse> closeDenoms = closeDenomRepo.findByShift(s).stream()
                .map(d -> PosShiftDenominationResponse.builder()
                        .denomination(d.getDenomination())
                        .quantity(d.getQuantity())
                        .total((long) d.getDenomination() * d.getQuantity())
                        .build())
                .collect(Collectors.toList());

        // Import Pack Quantity Map
        Map<Long, Integer> importMap = new HashMap<>();
        stockImportRepo.sumPackQtyByShiftId(s.getId())
                .forEach(row -> importMap.put((Long) row[0], ((Number) row[1]).intValue()));

        // ══════════════════════════════════════════════════════════
        // Tính tổng quantityUsed và chỉ làm tròn 2 chữ số thập phân
        // ══════════════════════════════════════════════════════════
        Map<Long, BigDecimal> soldMapDecimal = new HashMap<>();

        orderItemIngredientRepo.findByShiftId(s.getId())
                .forEach(ing -> soldMapDecimal.merge(
                        ing.getIngredientId(),
                        ing.getQuantityUsed(),
                        BigDecimal::add
                ));

        // Làm tròn 2 chữ số thập phân SAU KHI đã cộng xong
        Map<Long, BigDecimal> soldMap = new HashMap<>();
        soldMapDecimal.forEach((ingredientId, totalUsed) -> {
            BigDecimal rounded = totalUsed.setScale(2, RoundingMode.HALF_UP);
            soldMap.put(ingredientId, rounded);
        });
        // ══════════════════════════════════════════════════════════

        // Open Inventory
        List<PosShiftInventoryResponse> openInv = openInvRepo.findByShift(s).stream()
                .map(i -> PosShiftInventoryResponse.builder()
                        .ingredientId(i.getIngredientId())
                        .ingredientName(i.getIngredientName())
                        .ingredientImageUrl("")
                        .unitPerPack(i.getPackQuantity())
                        .packQuantity(i.getPackQuantity())
                        .unitQuantity(i.getUnitQuantity())
                        .totalUnits(i.getPackQuantity() * i.getPackQuantity()
                                + i.getUnitQuantity().doubleValue())
                        .importPackQty(importMap.getOrDefault(i.getIngredientId(), 0))
                        .soldQty(soldMap.getOrDefault(i.getIngredientId(), BigDecimal.ZERO))  // BigDecimal
                        .build())
                .collect(Collectors.toList());

        // Close Inventory
        List<PosShiftInventoryResponse> closeInv = closeInvRepo.findByShift(s).stream()
                .map(i -> PosShiftInventoryResponse.builder()
                        .ingredientId(i.getIngredientId())
                        .ingredientName(i.getIngredientName())
                        .ingredientImageUrl("")
                        .unitPerPack(i.getPackQuantity())
                        .packQuantity(i.getPackQuantity())
                        .unitQuantity(i.getUnitQuantity())
                        .totalUnits(i.getPackQuantity() * i.getPackQuantity()
                                + i.getUnitQuantity().doubleValue())
                        .build())
                .collect(Collectors.toList());

        // Orders & Revenue
        List<PosOrder> orders = orderRepo.findByShiftOrderByCreatedAtDesc(s);

        BigDecimal revenue = orders.stream()
                .filter(o -> o.getStatus() != PosOrderStatus.CANCELLED)
                .map(PosOrder::getFinalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalVat = orders.stream()
                .filter(o -> o.getStatus() != PosOrderStatus.CANCELLED)
                .flatMap(o -> orderItemRepo.findByOrder(o).stream())
                .map(item -> item.getVatAmount() != null ? item.getVatAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return PosShiftResponse.builder()
                .id(s.getId())
                .staffName(s.getStaffName())
                .status(s.getStatus())
                .shiftDate(s.getShiftDate())
                .isFirstShiftOfDay(s.getIsFirstShiftOfDay())
                .openTime(s.getOpenTime())
                .closeTime(s.getCloseTime())
                .openingCash(s.getOpeningCash())
                .closingCash(s.getClosingCash())
                .transferAmount(s.getTransferAmount())
                .note(s.getNote())
                .openDenominations(openDenoms)
                .closeDenominations(closeDenoms)
                .openInventory(openInv)
                .closeInventory(closeInv)
                .totalOrders(orders.size())
                .totalRevenue(revenue.add(totalVat))
                .build();
    }

    @Transactional
    public PosShiftInventoryResponse updateOpenInventoryItem(
            Long shiftId, Long ingredientId,
            int packQuantity, BigDecimal unitQuantity,   // ← BigDecimal
            Long storeId) {

        PosShift shift = shiftRepo.findById(shiftId)
                .orElseThrow(() -> new RuntimeException("Shift not found: " + shiftId));

        if (shift.getStatus() != ShiftStatus.OPEN)
            throw new RuntimeException("Chỉ được chỉnh sửa khi ca đang mở.");

        // Scale về 2 chữ số thập phân
        BigDecimal unitQty = unitQuantity != null
                ? unitQuantity.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        PosShiftOpenInventory inv = openInvRepo
                .findByShiftAndIngredient_Id(shiftId, ingredientId)
                .orElseThrow(() -> new RuntimeException(
                        "Không tìm thấy kho cho ingredient #" + ingredientId + " trong ca #" + shiftId));

        inv.setPackQuantity(packQuantity);
        inv.setUnitQuantity(unitQty);       // ← BigDecimal
        openInvRepo.save(inv);

        if (!Boolean.TRUE.equals(shift.getIsFirstShiftOfDay())) {
            shiftRepo.findLatestClosedShiftByStoreAndDate(
                    posUserStoreRepo.findByUserId(shift.getOpenedBy().getId())
                            .orElseThrow(() -> new RuntimeException("Store not found for shift"))
                            .getStore().getId(),
                    shift.getShiftDate()
            ).ifPresent(prevShift -> {
                closeInvRepo.findByShiftAndIngredient_Id(prevShift.getId(), ingredientId)
                        .ifPresent(prevClose -> {
                            prevClose.setPackQuantity(packQuantity);
                            prevClose.setUnitQuantity(unitQty);   // ← BigDecimal
                            closeInvRepo.save(prevClose);

                        });
            });
        }

        Map<Long, Integer> importMap = new HashMap<>();
        stockImportRepo.sumPackQtyByShiftId(shiftId)
                .forEach(row -> importMap.put((Long) row[0], ((Number) row[1]).intValue()));

        return PosShiftInventoryResponse.builder()
                .ingredientId(inv.getIngredientId())
                .ingredientName(inv.getIngredientName())
                .ingredientImageUrl("")
                .unitPerPack(inv.getPackQuantity())
                .packQuantity(inv.getPackQuantity())
                .unitQuantity(inv.getUnitQuantity())
                .totalUnits(inv.getPackQuantity() * inv.getPackQuantity()
                        + inv.getUnitQuantity().doubleValue())  // tổng = pack×unitPerPack + unitQty
                .importPackQty(importMap.getOrDefault(ingredientId, 0))
                .build();
    }

    private List<PosOrderItemResponse.VariantSelectionResponse> groupIngredientsByVariant(
            List<PosOrderItemIngredient> ingredients) {
        if (ingredients == null || ingredients.isEmpty()) return Collections.emptyList();

        Map<Long, List<PosOrderItemIngredient>> grouped = new LinkedHashMap<>();
        for (var si : ingredients) {
            Long key = si.getVariantId() != null ? si.getVariantId() : -1L;
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(si);
        }

        return grouped.entrySet().stream().map(e -> {
            List<PosOrderItemIngredient> group = e.getValue();
            return PosOrderItemResponse.VariantSelectionResponse.builder()
                    .variantId(e.getKey() == -1L ? null : e.getKey())
                    .variantGroupName(group.get(0).getVariantGroupName())
                    .selectedIngredients(group.stream().map(si ->
                                    PosOrderItemIngredientResponse.builder()
                                            .ingredientId(si.getIngredientId())
                                            .ingredientName(si.getIngredientName())
                                            .ingredientImageUrl(si.getIngredientImageUrl())
                                            .selectedCount(si.getSelectedCount())
                                            .defaultDeductPerUnit(si.getDefaultDeductPerUnit())
                                            .unitWeights(si.getUnitWeights())
                                            .quantityUsed(si.getQuantityUsed())
                                            .addonPrice(si.getAddonPriceSnapshot())  // ← THÊM
                                            .build())
                            .collect(Collectors.toList()))
                    .build();
        }).collect(Collectors.toList());
    }

    private void _upsertAppMenu(PosProduct p, AppPlatform platform, boolean enabled, BigDecimal price) {
        Optional<PosAppMenu> existing = appMenuRepo.findByProductAndPlatform(p, platform);
        if (!enabled) { existing.ifPresent(m -> { m.setIsActive(false); appMenuRepo.save(m); }); return; }
        if (existing.isPresent()) {
            PosAppMenu m = existing.get(); m.setIsActive(true);
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) m.setPrice(price);
            appMenuRepo.save(m);
        } else if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
            appMenuRepo.save(PosAppMenu.builder().product(p).platform(platform).price(price).isActive(true).build());
        }
    }

    private PosOrderResponse toOrderResponse(PosOrder o) {
        // ← Dùng repository thay vì o.getItems() để tránh lazy loading
        List<PosOrderItem> orderItems = orderItemRepo.findByOrder(o);

        List<PosOrderItemResponse> items = orderItems.isEmpty()
                ? Collections.emptyList()
                : orderItems.stream().map(item -> PosOrderItemResponse.builder()
                        .id(item.getId())
                        .productId(item.getProductId())
                        .productName(item.getProductName())
                        .productImageUrl(item.getProductImageUrl())
                        .basePrice(item.getBasePrice())
                        .defaultPrice(item.getDefaultPrice())
                        .discountPercent(item.getDiscountPercent())
                        .vatPercent(item.getVatPercent())
                        .vatAmount(item.getVatAmount())
                        .addonAmount(item.getAddonAmount())
                        .finalUnitPrice(item.getFinalUnitPrice())
                        .quantity(item.getQuantity())
                        .subtotal(item.getSubtotal())
                        .note(item.getNote())
                        .variantSelections(groupIngredientsByVariant(
                                orderItemIngredientRepo.findByOrderItem(item)))
                        .build())
                .collect(Collectors.toList());

        BigDecimal platformFee = BigDecimal.ZERO;
        BigDecimal netRevenue  = o.getFinalAmount();

        if (o.getOrderSource() == OrderSource.SHOPEE_FOOD
                || o.getOrderSource() == OrderSource.GRAB_FOOD) {
            PosStore store = posStoreRepo.findById(o.getStore().getId()).orElse(null);
            if (store != null) {
                BigDecimal rate = o.getOrderSource() == OrderSource.SHOPEE_FOOD
                        ? store.getShopeeRate() : store.getGrabRate();
                platformFee = o.getFinalAmount()
                        .multiply(rate)
                        .setScale(0, RoundingMode.HALF_UP);
                netRevenue  = o.getFinalAmount().subtract(platformFee);
            }
        }

        return PosOrderResponse.builder()
                .id(o.getId())
                .orderCode(o.getOrderCode())
                .shiftId(o.getShift().getId())
                .staffName(o.getShift().getStaffName())
                .orderSource(o.getOrderSource())
                .status(o.getStatus())
                .totalAmount(o.getTotalAmount())
                .discountAmount(o.getDiscountAmount())   // ← THÊM field này
                .finalAmount(o.getFinalAmount())
                .totalVat(o.getTotalVatAmount())
                .paymentMethod(o.getPaymentMethod())
                .note(o.getNote())
                .createdAt(o.getCreatedAt())
                .updatedAt(o.getUpdatedAt())
                .items(items)
                .platformFee(o.getPlatformFeeAmount())
                .platformRate(o.getPlatformRate())
                .netRevenue(netRevenue)
                .build();
    }

    private Long getStoreIdByUserId(Long userId) {
        return posUserStoreRepo.findByUserId(userId)
                .map(pus -> pus.getStore().getId())
                .orElseThrow(() -> new RuntimeException(
                        "User ID " + userId + " chưa được gán vào store nào"));
    }

    private String getStoreNameByUserId(Long userId) {
        return posUserStoreRepo.findByUserId(userId)
                .map(pus -> pus.getStore().getName())
                .orElseThrow(() -> new RuntimeException(
                        "User ID " + userId + " chưa được gán vào store nào"));
    }


    private StockImportResponse toStockImportResponse(PosShiftStockImport s) {
        return StockImportResponse.builder().id(s.getId()).ingredientId(s.getIngredientId())
                .ingredientName(s.getIngredientName()).packQty(s.getPackQty())
                .importedAt(s.getImportedAt()).build();
    }
}