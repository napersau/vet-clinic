package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.dto.request.ChangePriceRequest;
import com.vetclinic.clinical.dto.request.CreateServiceRequest;
import com.vetclinic.clinical.dto.request.LabTestItemRequest;
import com.vetclinic.clinical.dto.request.ReferenceRangeInput;
import com.vetclinic.clinical.dto.request.ReferenceRangeRequest;
import com.vetclinic.clinical.dto.request.ServiceCategoryRequest;
import com.vetclinic.clinical.dto.request.SpeciesRequest;
import com.vetclinic.clinical.dto.request.UpdateLabTestItemRequest;
import com.vetclinic.clinical.dto.request.UpdateServiceRequest;
import com.vetclinic.clinical.dto.response.LabTestItemResponse;
import com.vetclinic.clinical.dto.response.ReferenceRangeResponse;
import com.vetclinic.clinical.dto.response.ServiceCategoryResponse;
import com.vetclinic.clinical.dto.response.ServicePriceResponse;
import com.vetclinic.clinical.dto.response.ServiceResponse;
import com.vetclinic.clinical.dto.response.SpeciesResponse;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.LabTestItem;
import com.vetclinic.clinical.entity.LabTestReferenceRange;
import com.vetclinic.clinical.entity.ServiceCategory;
import com.vetclinic.clinical.entity.ServiceLabTestItem;
import com.vetclinic.clinical.entity.ServicePrice;
import com.vetclinic.clinical.entity.Species;
import com.vetclinic.clinical.mapper.ClinicalMapper;
import com.vetclinic.clinical.repository.ClinicServiceRepository;
import com.vetclinic.clinical.repository.LabTestItemRepository;
import com.vetclinic.clinical.repository.LabTestReferenceRangeRepository;
import com.vetclinic.clinical.repository.ServiceCategoryRepository;
import com.vetclinic.clinical.repository.ServiceLabTestItemRepository;
import com.vetclinic.clinical.repository.ServicePriceRepository;
import com.vetclinic.clinical.repository.SpeciesRepository;
import com.vetclinic.clinical.service.CatalogService;
import com.vetclinic.clinical.service.ServicePriceBook;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CatalogServiceImpl implements CatalogService {

    private static final String FIRST_PRICE_REASON = "Bảng giá khởi tạo";

    private final SpeciesRepository speciesRepository;
    private final ServiceCategoryRepository categoryRepository;
    private final ClinicServiceRepository serviceRepository;
    private final ServicePriceRepository priceRepository;
    private final ServicePriceBook priceBook;
    private final LabTestItemRepository labTestItemRepository;
    private final LabTestReferenceRangeRepository rangeRepository;
    private final ServiceLabTestItemRepository serviceLabItemRepository;
    private final ClinicalMapper mapper;
    private final Clock clock;

    // ------------------------------------------------------------------ loài

    @Override
    @Transactional(readOnly = true)
    public List<SpeciesResponse> listSpecies() {
        return speciesRepository.findAll().stream().map(mapper::toResponse).toList();
    }

    @Override
    @Transactional
    public SpeciesResponse createSpecies(SpeciesRequest request) {
        String code = normalizeCode(request.code());
        if (speciesRepository.existsByCode(code)) {
            throw new AppException(ErrorCode.CODE_ALREADY_EXISTS, "Mã loài đã tồn tại");
        }
        Species species = Species.builder()
                .code(code).name(request.name().trim()).description(blankToNull(request.description())).build();
        return mapper.toResponse(speciesRepository.saveAndFlush(species));
    }

    @Override
    @Transactional
    public SpeciesResponse updateSpecies(Short id, SpeciesRequest request) {
        Species species = speciesRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.SPECIES_NOT_FOUND));
        String code = normalizeCode(request.code());
        if (speciesRepository.existsByCodeAndIdNot(code, id)) {
            throw new AppException(ErrorCode.CODE_ALREADY_EXISTS, "Mã loài đã tồn tại");
        }
        species.setCode(code);
        species.setName(request.name().trim());
        species.setDescription(blankToNull(request.description()));
        if (request.active() != null) {
            species.setActive(request.active());   // xóa mềm: hồ sơ thú cưng cũ vẫn tham chiếu được
        }
        return mapper.toResponse(speciesRepository.saveAndFlush(species));
    }

    // ------------------------------------------------------------------ nhóm dịch vụ

    @Override
    @Transactional(readOnly = true)
    public List<ServiceCategoryResponse> listCategories(boolean activeOnly) {
        return (activeOnly ? categoryRepository.findByActiveTrueOrderByDisplayOrderAscIdAsc()
                : categoryRepository.findAllByOrderByDisplayOrderAscIdAsc()).stream().map(CatalogServiceImpl::toCategory).toList();
    }

    @Override
    @Transactional
    public ServiceCategoryResponse createCategory(ServiceCategoryRequest request) {
        String code = normalizeCode(request.code());
        if (categoryRepository.existsByCode(code)) {
            throw new AppException(ErrorCode.CODE_ALREADY_EXISTS, "Mã nhóm dịch vụ đã tồn tại");
        }
        ServiceCategory category = ServiceCategory.builder()
                .code(code)
                .name(request.name().trim())
                .queueType(blankToNull(request.queueType()))
                // Mặc định thu trước khi nhóm vào một hàng đợi (khám, XN, CĐHA); nhóm làm tại phòng thu khi chốt ca
                .requiresPrepayment(request.requiresPrepayment() != null ? request.requiresPrepayment() : blankToNull(request.queueType()) != null)
                .displayOrder(request.displayOrder() == null ? 0 : request.displayOrder())
                .active(request.active() == null || request.active())
                .build();
        return toCategory(categoryRepository.saveAndFlush(category));
    }

    @Override
    @Transactional
    public ServiceCategoryResponse updateCategory(Short id, ServiceCategoryRequest request) {
        ServiceCategory category = categoryRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_INPUT_DATA, "Không tìm thấy nhóm dịch vụ"));
        category.setName(request.name().trim());
        category.setQueueType(blankToNull(request.queueType()));
        if (request.requiresPrepayment() != null) {
            category.setRequiresPrepayment(request.requiresPrepayment());
        }
        if (request.displayOrder() != null) {
            category.setDisplayOrder(request.displayOrder());
        }
        if (request.active() != null) {
            category.setActive(request.active());
        }
        return toCategory(categoryRepository.saveAndFlush(category));
    }

    // ------------------------------------------------------------------ dịch vụ + bảng giá

    @Override
    @Transactional(readOnly = true)
    public List<ServiceResponse> listServices(boolean activeOnly, String categoryCode) {
        List<ClinicService> services = serviceRepository.search(activeOnly, blankToNull(categoryCode));
        if (services.isEmpty()) {
            return List.of();
        }
        Map<UUID, ServicePrice> prices = priceRepository
                .findEffectiveForServices(services.stream().map(ClinicService::getId).toList(), clock.instant()).stream()
                .collect(Collectors.toMap(p -> p.getService().getId(), Function.identity(), (a, b) -> a));
        return services.stream().map(s -> toResponse(s, prices.get(s.getId()))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ServiceResponse getService(UUID id) {
        ClinicService service = findService(id);
        return toResponse(service, priceRepository.findEffective(id, clock.instant()).orElse(null));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceResponse createService(CreateServiceRequest request) {
        String code = normalizeCode(request.code());
        if (serviceRepository.existsByCode(code)) {
            throw new AppException(ErrorCode.CODE_ALREADY_EXISTS, "Mã dịch vụ đã tồn tại");
        }
        ServiceCategory category = categoryRepository.findByCode(normalizeCode(request.categoryCode()))
                .filter(ServiceCategory::isActive)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_INPUT_DATA, "Nhóm dịch vụ không tồn tại hoặc đã ngừng sử dụng"));
        ClinicService service = serviceRepository.saveAndFlush(ClinicService.builder()
                .category(category)
                .code(code)
                .name(request.name().trim())
                .description(blankToNull(request.description()))
                .unit(blankToNull(request.unit()) == null ? "Lần" : request.unit().trim())
                .estimatedMinutes(request.estimatedMinutes())
                .preparationNotes(blankToNull(request.preparationNotes()))
                .active(true)
                .build());
        BigDecimal vat = request.vatRate() == null ? BigDecimal.ZERO.setScale(2) : request.vatRate();
        ServicePrice price = priceBook.openFirstPrice(service, request.price(), vat, FIRST_PRICE_REASON, SecurityUtils.getCurrentUserId());
        return toResponse(service, price);
    }

    @Override
    @Transactional
    public ServiceResponse updateService(UUID id, UpdateServiceRequest request) {
        ClinicService service = findService(id);
        if (request.name() != null) {
            service.setName(request.name().trim());
        }
        if (request.unit() != null) {
            service.setUnit(request.unit().trim());
        }
        if (request.estimatedMinutes() != null) {
            service.setEstimatedMinutes(request.estimatedMinutes());
        }
        if (request.preparationNotes() != null) {
            service.setPreparationNotes(blankToNull(request.preparationNotes()));
        }
        if (request.description() != null) {
            service.setDescription(blankToNull(request.description()));
        }
        if (request.active() != null) {
            service.setActive(request.active());
        }
        serviceRepository.saveAndFlush(service);
        return toResponse(service, priceRepository.findEffective(id, clock.instant()).orElse(null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServicePriceResponse> listPrices(UUID serviceId) {
        findService(serviceId);
        Instant now = clock.instant();
        return priceRepository.findByServiceIdOrderByEffectiveFromDesc(serviceId).stream().map(p -> toPrice(p, now)).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServicePriceResponse changePrice(UUID serviceId, ChangePriceRequest request) {
        ClinicService service = findService(serviceId);
        Instant now = clock.instant();
        Instant effectiveFrom = request.effectiveFrom() == null ? now : request.effectiveFrom();   // ServicePriceBook cắt về giây (DATETIME)
        ServicePrice created = priceBook.changePrice(service, request.price(), request.vatRate(), effectiveFrom,
                blankToNull(request.reason()), SecurityUtils.getCurrentUserId());
        return toPrice(created, now);
    }

    // ------------------------------------------------------------------ chỉ số xét nghiệm

    @Override
    @Transactional(readOnly = true)
    public List<LabTestItemResponse> listLabTestItems() {
        List<LabTestItem> items = labTestItemRepository.findAllByOrderByCodeAsc();
        Map<Short, List<ReferenceRangeResponse>> ranges = items.isEmpty() ? Map.of()
                : rangeRepository.findByLabTestItemIdIn(items.stream().map(LabTestItem::getId).toList()).stream()
                .collect(Collectors.groupingBy(r -> r.getLabTestItem().getId(),
                        Collectors.mapping(this::toRange, Collectors.toList())));
        return items.stream().map(i -> toItem(i, ranges.getOrDefault(i.getId(), List.of()))).toList();
    }

    @Override
    @Transactional
    public LabTestItemResponse createLabTestItem(LabTestItemRequest request) {
        String code = normalizeCode(request.code());
        if (labTestItemRepository.existsByCode(code)) {
            throw new AppException(ErrorCode.CODE_ALREADY_EXISTS, "Mã chỉ số đã tồn tại");
        }
        List<ReferenceRangeInput> ranges = request.ranges() == null ? List.of() : request.ranges();
        Set<Short> speciesIds = new LinkedHashSet<>();
        for (ReferenceRangeInput range : ranges) {
            requireValidRange(range.minValue(), range.maxValue());
            if (!speciesIds.add(range.speciesId())) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mỗi loài chỉ được khai báo một khoảng tham chiếu");
            }
        }
        LabTestItem item;
        try {
            item = labTestItemRepository.saveAndFlush(LabTestItem.builder().code(code).name(request.name().trim())
                    .unit(request.unit().trim()).description(blankToNull(request.description())).build());
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.CODE_ALREADY_EXISTS, "Mã chỉ số đã tồn tại");
        }
        for (ReferenceRangeInput range : ranges) {
            Species species = speciesRepository.findById(range.speciesId())
                    .orElseThrow(() -> new AppException(ErrorCode.SPECIES_NOT_FOUND));
            rangeRepository.save(newRange(item, species, range.minValue(), range.maxValue()));
        }
        return itemResponse(item);
    }

    @Override
    @Transactional
    public LabTestItemResponse updateLabTestItem(Short id, UpdateLabTestItemRequest request) {
        LabTestItem item = labTestItemRepository.findById(id).orElseThrow(() -> new AppException(ErrorCode.LAB_ITEM_NOT_FOUND));
        item.setName(request.name().trim());
        item.setUnit(request.unit().trim());
        item.setDescription(blankToNull(request.description()));
        if (request.active() != null) {
            item.setActive(request.active());
        }
        return itemResponse(labTestItemRepository.saveAndFlush(item));
    }

    @Override
    @Transactional
    public LabTestItemResponse saveReferenceRange(Short labTestItemId, Short speciesId, ReferenceRangeRequest request) {
        requireValidRange(request.minValue(), request.maxValue());
        LabTestItem item = labTestItemRepository.findById(labTestItemId)
                .orElseThrow(() -> new AppException(ErrorCode.LAB_ITEM_NOT_FOUND));
        Species species = speciesRepository.findById(speciesId).orElseThrow(() -> new AppException(ErrorCode.SPECIES_NOT_FOUND));
        LabTestReferenceRange range = rangeRepository.findByLabTestItemIdAndSpeciesId(labTestItemId, speciesId)
                .orElseGet(() -> newRange(item, species, request.minValue(), request.maxValue()));
        range.setMinValue(request.minValue());
        range.setMaxValue(request.maxValue());
        rangeRepository.saveAndFlush(range);
        return itemResponse(item);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabTestItemResponse> listServiceLabTestItems(UUID serviceId) {
        requireLabService(serviceId);
        return itemsOfService(serviceId);
    }

    @Override
    @Transactional
    public List<LabTestItemResponse> setServiceLabTestItems(UUID serviceId, List<Short> labTestItemIds) {
        requireLabService(serviceId);
        Set<Short> ids = new LinkedHashSet<>(labTestItemIds);
        if (labTestItemRepository.findAllById(ids).size() != ids.size()) {
            throw new AppException(ErrorCode.LAB_ITEM_NOT_FOUND);
        }
        serviceLabItemRepository.deleteByServiceId(serviceId);
        serviceLabItemRepository.saveAll(ids.stream().map(itemId -> new ServiceLabTestItem(serviceId, itemId)).toList());
        return itemsOfService(serviceId);
    }

    // ------------------------------------------------------------------ helper

    private ClinicService findService(UUID id) {
        return serviceRepository.findById(id).orElseThrow(() -> new AppException(ErrorCode.SERVICE_NOT_FOUND));
    }

    private void requireLabService(UUID serviceId) {
        ClinicService service = findService(serviceId);
        if (!service.getCategory().is(ServiceCategory.LABORATORY)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Chỉ dịch vụ xét nghiệm (LABORATORY) mới có danh sách chỉ số");
        }
    }

    /** Chưa cấu hình dòng nào thì dịch vụ được coi là gồm mọi chỉ số (tương thích ngược). */
    private List<LabTestItemResponse> itemsOfService(UUID serviceId) {
        Set<Short> configured = new LinkedHashSet<>(serviceLabItemRepository.findItemIdsByServiceId(serviceId));
        return listLabTestItems().stream().filter(i -> configured.isEmpty() || configured.contains(i.id())).toList();
    }

    private LabTestItemResponse itemResponse(LabTestItem item) {
        List<ReferenceRangeResponse> ranges = rangeRepository.findByLabTestItemIdIn(List.of(item.getId())).stream().map(this::toRange).toList();
        return toItem(item, ranges);
    }

    private static LabTestItemResponse toItem(LabTestItem i, List<ReferenceRangeResponse> ranges) {
        return new LabTestItemResponse(i.getId(), i.getCode(), i.getName(), i.getUnit(), i.getDescription(), i.isActive(), ranges);
    }

    private static LabTestReferenceRange newRange(LabTestItem item, Species species, BigDecimal min, BigDecimal max) {
        return LabTestReferenceRange.builder()
                .id(new LabTestReferenceRange.Key(item.getId(), species.getId()))
                .labTestItem(item).species(species).minValue(min).maxValue(max).build();
    }

    private static void requireValidRange(BigDecimal min, BigDecimal max) {
        if (min.compareTo(max) >= 0) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Giá trị ngưỡng dưới phải nhỏ hơn ngưỡng trên");
        }
    }

    private ReferenceRangeResponse toRange(LabTestReferenceRange r) {
        return new ReferenceRangeResponse(r.getSpecies().getId(), r.getSpecies().getCode(), r.getMinValue(), r.getMaxValue());
    }

    private static ServiceCategoryResponse toCategory(ServiceCategory c) {
        return new ServiceCategoryResponse(c.getId(), c.getCode(), c.getName(), c.getQueueType(), c.isRequiresPrepayment(),
                c.getDisplayOrder(), c.isActive());
    }

    private static ServiceResponse toResponse(ClinicService s, ServicePrice price) {
        ServiceCategory c = s.getCategory();
        return new ServiceResponse(s.getId(), s.getCode(), s.getName(), c.getId(), c.getCode(), c.getName(), c.getQueueType(),
                c.isRequiresPrepayment(), s.getUnit(), s.getEstimatedMinutes(), s.getPreparationNotes(), s.getDescription(), s.isActive(),
                price == null ? null : price.getPrice(), price == null ? null : price.getVatRate(),
                price == null ? null : price.getEffectiveFrom());
    }

    private static ServicePriceResponse toPrice(ServicePrice p, Instant now) {
        boolean current = !p.getEffectiveFrom().isAfter(now) && (p.getEffectiveTo() == null || p.getEffectiveTo().isAfter(now));
        return new ServicePriceResponse(p.getId(), p.getPrice(), p.getVatRate(), p.getEffectiveFrom(), p.getEffectiveTo(),
                p.getChangeReason(), p.getCreatedBy(), p.getCreatedAt(), current);
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
