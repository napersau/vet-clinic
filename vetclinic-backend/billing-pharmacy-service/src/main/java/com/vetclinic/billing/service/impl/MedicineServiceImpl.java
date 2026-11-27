package com.vetclinic.billing.service.impl;

import com.vetclinic.billing.constant.InventoryTransactionType;
import com.vetclinic.billing.dto.request.ChangePriceRequest;
import com.vetclinic.billing.dto.request.IngredientInput;
import com.vetclinic.billing.dto.request.InventoryAdjustRequest;
import com.vetclinic.billing.dto.request.InventoryImportRequest;
import com.vetclinic.billing.dto.request.MedicineCategoryRequest;
import com.vetclinic.billing.dto.request.MedicineRequest;
import com.vetclinic.billing.dto.request.UpdateMedicineRequest;
import com.vetclinic.billing.dto.response.IngredientResponse;
import com.vetclinic.billing.dto.response.InventoryTransactionResponse;
import com.vetclinic.billing.dto.response.MedicineCategoryResponse;
import com.vetclinic.billing.dto.response.MedicinePriceResponse;
import com.vetclinic.billing.dto.response.MedicineResponse;
import com.vetclinic.billing.entity.InventoryTransaction;
import com.vetclinic.billing.entity.Medicine;
import com.vetclinic.billing.entity.MedicineCategory;
import com.vetclinic.billing.entity.MedicineIngredient;
import com.vetclinic.billing.entity.MedicinePrice;
import com.vetclinic.billing.entity.MedicineStock;
import com.vetclinic.billing.repository.InventoryTransactionRepository;
import com.vetclinic.billing.repository.MedicineCategoryRepository;
import com.vetclinic.billing.repository.MedicinePriceRepository;
import com.vetclinic.billing.repository.MedicineRepository;
import com.vetclinic.billing.repository.MedicineSpecifications;
import com.vetclinic.billing.repository.MedicineStockRepository;
import com.vetclinic.billing.service.BillingAssembler;
import com.vetclinic.billing.service.MedicineInvoiceAmounts;
import com.vetclinic.billing.service.MedicinePriceBook;
import com.vetclinic.billing.service.MedicineService;
import com.vetclinic.billing.service.StockAlertPublisher;
import com.vetclinic.common.dto.PageResponse;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import com.vetclinic.common.security.SecurityUtils;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MedicineServiceImpl implements MedicineService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final BigDecimal DEFAULT_VAT = new BigDecimal("0.05");

    private final MedicineRepository medicineRepository;
    private final MedicineCategoryRepository categoryRepository;
    private final MedicineStockRepository stockRepository;
    private final MedicinePriceRepository priceRepository;
    private final MedicinePriceBook priceBook;
    private final InventoryTransactionRepository inventoryRepository;
    private final BillingAssembler assembler;
    private final StockAlertPublisher stockAlerts;
    private final EntityManager entityManager;
    private final Clock clock;

    // ================================================================== tra cứu

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MedicineResponse> search(String keyword, String categoryCode, Boolean active, boolean lowStockOnly, int page, int size) {
        // Chỉ Admin được xem thuốc đã ngừng kinh doanh; còn lại luôn bị ép lọc đang kinh doanh
        Boolean effectiveActive = isAdmin() ? active : Boolean.TRUE;
        Page<Medicine> result = medicineRepository.findAll(
                MedicineSpecifications.filter(keyword, categoryCode, effectiveActive, lowStockOnly),
                PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by("name").ascending().and(Sort.by("id"))));
        // Một truy vấn giá hiện hành cho cả trang (không N+1)
        Map<UUID, MedicinePrice> prices = currentPrices(result.getContent().stream().map(Medicine::getId).toList());
        return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isLast(), m -> toResponse(m, prices.get(m.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public MedicineResponse getById(UUID id) {
        Medicine medicine = find(id);
        if (!medicine.isActive() && !isAdmin()) {
            throw new AppException(ErrorCode.MEDICINE_NOT_FOUND);
        }
        return toResponse(medicine, priceRepository.findEffective(id, clock.instant()).orElse(null));
    }

    // ================================================================== thêm / sửa

    @Override
    @Transactional
    public MedicineResponse create(MedicineRequest request) {
        UUID actor = SecurityUtils.getCurrentUserId();
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (medicineRepository.existsByCodeIgnoreCase(code)) {
            throw new AppException(ErrorCode.MEDICINE_CODE_ALREADY_EXISTS);
        }
        MedicineCategory category = activeCategory(request.categoryCode());
        int initialStock = request.initialStock() == null ? 0 : request.initialStock();
        Medicine medicine = Medicine.builder()
                .category(category)
                .code(code)
                .name(request.name().trim())
                .dosageForm(request.dosageForm().trim())
                .strength(blankToNull(request.strength()))
                .baseUnit(request.baseUnit().trim())
                .packagingSpec(request.packagingSpec().trim())
                .manufacturer(blankToNull(request.manufacturer()))
                .countryOfOrigin(blankToNull(request.countryOfOrigin()))
                .registrationNumber(blankToNull(request.registrationNumber()))
                .storageConditions(blankToNull(request.storageConditions()))
                .requiresPrescription(request.requiresPrescription() == null || request.requiresPrescription())
                .minStockAlert(request.minStockAlert() == null ? 10 : request.minStockAlert())
                .build();
        for (IngredientInput in : distinctIngredients(request.ingredients())) {
            medicine.addIngredient(toIngredient(in));
        }
        try {
            medicine = medicineRepository.saveAndFlush(medicine);
        } catch (DataIntegrityViolationException e) {
            // Hai Admin cùng tạo một mã: ràng buộc UNIQUE là chốt chặn cuối
            throw new AppException(ErrorCode.MEDICINE_CODE_ALREADY_EXISTS);
        }
        MedicineStock stock = stockRepository.saveAndFlush(MedicineStock.builder().medicine(medicine).quantityOnHand(initialStock).build());
        medicine.setStock(stock);
        BigDecimal vat = request.vatRate() == null ? DEFAULT_VAT : MedicineInvoiceAmounts.money(request.vatRate());
        MedicinePrice price = priceBook.openFirstPrice(medicine, MedicineInvoiceAmounts.money(request.salePrice()), vat, "Giá khởi tạo", actor);
        if (initialStock > 0) {
            log(medicine.getId(), InventoryTransactionType.IMPORT, initialStock, null, "Tồn kho ban đầu khi tạo thuốc", actor);
        }
        return toResponse(medicine, price);
    }

    @Override
    @Transactional
    public MedicineResponse update(UUID id, UpdateMedicineRequest request) {
        Medicine medicine = find(id);
        if (request.categoryCode() != null && !request.categoryCode().isBlank()) {
            medicine.setCategory(activeCategory(request.categoryCode()));
        }
        medicine.setName(request.name().trim());
        medicine.setDosageForm(request.dosageForm().trim());
        medicine.setStrength(blankToNull(request.strength()));
        medicine.setBaseUnit(request.baseUnit().trim());
        medicine.setPackagingSpec(request.packagingSpec().trim());
        medicine.setManufacturer(blankToNull(request.manufacturer()));
        medicine.setCountryOfOrigin(blankToNull(request.countryOfOrigin()));
        medicine.setRegistrationNumber(blankToNull(request.registrationNumber()));
        medicine.setStorageConditions(blankToNull(request.storageConditions()));
        if (request.requiresPrescription() != null) {
            medicine.setRequiresPrescription(request.requiresPrescription());
        }
        if (request.minStockAlert() != null) {
            medicine.setMinStockAlert(request.minStockAlert());
        }
        if (request.active() != null) {
            medicine.setActive(request.active());
        }
        if (request.ingredients() != null) {
            replaceIngredients(medicine, distinctIngredients(request.ingredients()));
        }
        Medicine saved = medicineRepository.saveAndFlush(medicine);
        return toResponse(saved, priceRepository.findEffective(id, clock.instant()).orElse(null));
    }

    /** Thay danh sách thành phần theo TÊN (sửa dòng cũ, bỏ dòng không còn, thêm dòng mới): xóa rồi thêm lại cùng tên sẽ vi phạm UNIQUE vì INSERT chạy trước DELETE. */
    private void replaceIngredients(Medicine medicine, List<IngredientInput> wanted) {
        Map<String, MedicineIngredient> existing = medicine.getIngredients().stream()
                .collect(Collectors.toMap(i -> key(i.getIngredientName()), Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Set<String> keep = new HashSet<>();
        for (IngredientInput in : wanted) {
            String k = key(in.ingredientName());
            keep.add(k);
            MedicineIngredient current = existing.get(k);
            if (current == null) {
                medicine.addIngredient(toIngredient(in));
            } else {
                current.setIngredientName(in.ingredientName().trim());
                current.setStrengthValue(in.strengthValue());
                current.setStrengthUnit(blankToNull(in.strengthUnit()));
            }
        }
        medicine.getIngredients().removeIf(i -> !keep.contains(key(i.getIngredientName())));
    }

    // ================================================================== bảng giá

    @Override
    @Transactional(readOnly = true)
    public List<MedicinePriceResponse> prices(UUID id) {
        find(id);
        Instant now = clock.instant();
        return priceRepository.findByMedicineIdOrderByEffectiveFromDesc(id).stream().map(p -> toPrice(p, now)).toList();
    }

    @Override
    @Transactional
    public MedicinePriceResponse changePrice(UUID id, ChangePriceRequest request) {
        Medicine medicine = find(id);
        Instant now = clock.instant();
        Instant effectiveFrom = request.effectiveFrom() == null ? now : request.effectiveFrom();   // MedicinePriceBook cắt về giây (DATETIME)
        MedicinePrice created = priceBook.changePrice(medicine, MedicineInvoiceAmounts.money(request.salePrice()),
                MedicineInvoiceAmounts.money(request.vatRate()), effectiveFrom, blankToNull(request.reason()), SecurityUtils.getCurrentUserId());
        return toPrice(created, now);
    }

    // ================================================================== kho

    @Override
    @Transactional
    public MedicineResponse importStock(UUID id, InventoryImportRequest request) {
        UUID actor = SecurityUtils.getCurrentUserId();
        Medicine medicine = find(id);
        applyDelta(medicine, request.quantity());
        log(medicine.getId(), InventoryTransactionType.IMPORT, request.quantity(),
                request.unitCost() == null ? null : MedicineInvoiceAmounts.money(request.unitCost()), blankToNull(request.notes()), actor);
        return toResponse(medicine, priceRepository.findEffective(id, clock.instant()).orElse(null));
    }

    @Override
    @Transactional
    public MedicineResponse adjustStock(UUID id, InventoryAdjustRequest request) {
        UUID actor = SecurityUtils.getCurrentUserId();
        if (request.delta() == 0) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Số lượng điều chỉnh phải khác 0");
        }
        Medicine medicine = find(id);
        applyDelta(medicine, request.delta());
        log(medicine.getId(), InventoryTransactionType.ADJUSTMENT, request.delta(), null, request.reason().trim(), actor);
        if (request.delta() < 0) {
            stockAlerts.publishIfLow(medicine, actor);
        }
        return toResponse(medicine, priceRepository.findEffective(id, clock.instant()).orElse(null));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InventoryTransactionResponse> transactions(UUID id, int page, int size) {
        find(id);
        Page<InventoryTransaction> result = inventoryRepository.findByMedicineIdOrderByIdDesc(id,
                PageRequest.of(Math.max(page, 0), clampSize(size)));
        return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isLast(), assembler::toResponse);
    }

    // ================================================================== nhóm thuốc

    @Override
    @Transactional(readOnly = true)
    public List<MedicineCategoryResponse> categories(boolean includeInactive) {
        return (includeInactive && isAdmin() ? categoryRepository.findAllByOrderByNameAsc() : categoryRepository.findByActiveTrueOrderByNameAsc())
                .stream().map(MedicineServiceImpl::toCategory).toList();
    }

    @Override
    @Transactional
    public MedicineCategoryResponse createCategory(MedicineCategoryRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (categoryRepository.existsByCodeIgnoreCase(code)) {
            throw new AppException(ErrorCode.CODE_ALREADY_EXISTS, "Mã nhóm thuốc đã tồn tại");
        }
        try {
            return toCategory(categoryRepository.saveAndFlush(MedicineCategory.builder().code(code).name(request.name().trim())
                    .active(request.active() == null || request.active()).build()));
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.CODE_ALREADY_EXISTS, "Mã nhóm thuốc đã tồn tại");
        }
    }

    @Override
    @Transactional
    public MedicineCategoryResponse updateCategory(Short id, MedicineCategoryRequest request) {
        MedicineCategory category = categoryRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_INPUT_DATA, "Nhóm thuốc không tồn tại"));
        category.setName(request.name().trim());
        if (request.active() != null) {
            category.setActive(request.active());
        }
        return toCategory(categoryRepository.saveAndFlush(category));
    }

    // ================================================================== helper

    private Medicine find(UUID id) {
        return medicineRepository.findWithCategoryAndStockById(id).orElseThrow(() -> new AppException(ErrorCode.MEDICINE_NOT_FOUND));
    }

    private MedicineCategory activeCategory(String code) {
        MedicineCategory category = categoryRepository.findByCode(code.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_INPUT_DATA, "Nhóm thuốc [" + code + "] không tồn tại"));
        if (!category.isActive()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Nhóm thuốc [" + code + "] đã ngừng sử dụng");
        }
        return category;
    }

    private Map<UUID, MedicinePrice> currentPrices(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, MedicinePrice> prices = new HashMap<>();
        for (MedicinePrice p : priceRepository.findEffectiveForMedicines(ids, clock.instant())) {
            prices.put(p.getMedicine().getId(), p);
        }
        return prices;
    }

    /**
     * Đổi tồn kho bằng UPDATE nguyên tử rồi refresh để lấy số thật: không gán quantityOnHand trên entity (xem MedicineStock). 0 dòng = tồn kho sẽ
     * âm (thuốc đã được find nên chắc chắn tồn tại).
     */
    private void applyDelta(Medicine medicine, int delta) {
        if (stockRepository.add(medicine.getId(), delta) == 0) {
            throw new AppException(ErrorCode.INVALID_STOCK_ADJUSTMENT,
                    "Điều chỉnh làm tồn kho của thuốc [" + medicine.getName() + "] bị âm");
        }
        entityManager.refresh(medicine);   // REFRESH lan sang dòng tồn kho
    }

    /** quantity có dấu (+ nhập / - xuất): bất biến quantity_on_hand = SUM(quantity). */
    private void log(UUID medicineId, InventoryTransactionType type, int quantity, BigDecimal unitCost, String notes, UUID actor) {
        inventoryRepository.save(InventoryTransaction.builder().medicineId(medicineId).transactionType(type)
                .quantity(quantity).unitCost(unitCost).notes(notes).createdBy(actor).build());
    }

    /** Tên hoạt chất không trùng nhau trong một thuốc (UNIQUE medicine_id + ingredient_name); so sánh không phân biệt hoa thường. */
    private static List<IngredientInput> distinctIngredients(List<IngredientInput> list) {
        if (list == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<IngredientInput> result = new ArrayList<>();
        for (IngredientInput in : list) {
            if (!seen.add(key(in.ingredientName()))) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Hoạt chất [" + in.ingredientName().trim() + "] bị trùng trong danh sách thành phần");
            }
            result.add(in);
        }
        return result;
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static MedicineIngredient toIngredient(IngredientInput in) {
        return MedicineIngredient.builder().ingredientName(in.ingredientName().trim()).strengthValue(in.strengthValue())
                .strengthUnit(blankToNull(in.strengthUnit())).build();
    }

    private static MedicineCategoryResponse toCategory(MedicineCategory c) {
        return new MedicineCategoryResponse(c.getId(), c.getCode(), c.getName(), c.isActive());
    }

    private static MedicinePriceResponse toPrice(MedicinePrice p, Instant now) {
        boolean current = !p.getEffectiveFrom().isAfter(now) && (p.getEffectiveTo() == null || p.getEffectiveTo().isAfter(now));
        return new MedicinePriceResponse(p.getId(), p.getSalePrice(), p.getVatRate(), p.getEffectiveFrom(), p.getEffectiveTo(),
                p.getChangeReason(), p.getCreatedBy(), p.getCreatedAt(), current);
    }

    static MedicineResponse toResponse(Medicine m, MedicinePrice price) {
        List<IngredientResponse> ingredients = m.getIngredients().stream()
                .map(i -> new IngredientResponse(i.getIngredientName(), i.getStrengthValue(), i.getStrengthUnit())).toList();
        int stock = m.getStockQuantity();
        return new MedicineResponse(m.getId(), m.getCategory().getId(), m.getCategory().getCode(), m.getCategory().getName(), m.getCode(),
                m.getName(), m.getDosageForm(), m.getStrength(), m.getBaseUnit(), m.getPackagingSpec(), m.getManufacturer(),
                m.getCountryOfOrigin(), m.getRegistrationNumber(), m.getStorageConditions(), m.isRequiresPrescription(), ingredients,
                price == null ? null : price.getSalePrice(), price == null ? null : price.getVatRate(),
                price == null ? null : price.getEffectiveFrom(), stock, m.getMinStockAlert(), stock <= m.getMinStockAlert(), m.isActive());
    }

    private static boolean isAdmin() {
        return SecurityUtils.getCurrentRole().filter(r -> r == RoleCode.ADMIN).isPresent();
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
