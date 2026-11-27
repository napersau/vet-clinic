package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.dto.request.ChangePriceRequest;
import com.vetclinic.clinical.dto.request.CreateServiceRequest;
import com.vetclinic.clinical.dto.request.LabTestItemRequest;
import com.vetclinic.clinical.dto.request.ReferenceRangeInput;
import com.vetclinic.clinical.dto.request.ReferenceRangeRequest;
import com.vetclinic.clinical.dto.request.ServiceCategoryRequest;
import com.vetclinic.clinical.dto.request.UpdateLabTestItemRequest;
import com.vetclinic.clinical.dto.request.UpdateServiceRequest;
import com.vetclinic.clinical.dto.response.LabTestItemResponse;
import com.vetclinic.clinical.dto.response.ServiceCategoryResponse;
import com.vetclinic.clinical.dto.response.ServicePriceResponse;
import com.vetclinic.clinical.dto.response.ServiceResponse;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.LabTestItem;
import com.vetclinic.clinical.entity.LabTestReferenceRange;
import com.vetclinic.clinical.entity.ServiceCategory;
import com.vetclinic.clinical.entity.ServiceLabTestItem;
import com.vetclinic.clinical.entity.ServicePrice;
import com.vetclinic.clinical.mapper.ClinicalMapper;
import com.vetclinic.clinical.repository.ClinicServiceRepository;
import com.vetclinic.clinical.repository.LabTestItemRepository;
import com.vetclinic.clinical.repository.LabTestReferenceRangeRepository;
import com.vetclinic.clinical.repository.ServiceCategoryRepository;
import com.vetclinic.clinical.repository.ServiceLabTestItemRepository;
import com.vetclinic.clinical.repository.ServicePriceRepository;
import com.vetclinic.clinical.repository.SpeciesRepository;
import com.vetclinic.clinical.service.ServicePriceBook;
import com.vetclinic.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.vetclinic.clinical.testsupport.TestData.ADMIN_ID_FOR_PRICES;
import static com.vetclinic.clinical.testsupport.TestData.CLOCK;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static com.vetclinic.clinical.testsupport.TestData.category;
import static com.vetclinic.clinical.testsupport.TestData.dog;
import static com.vetclinic.clinical.testsupport.TestData.loginAs;
import static com.vetclinic.clinical.testsupport.TestData.service;
import static com.vetclinic.clinical.testsupport.TestData.sid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogServiceImplTest {

    @Mock
    private SpeciesRepository speciesRepository;
    @Mock
    private ServiceCategoryRepository categoryRepository;
    @Mock
    private ClinicServiceRepository serviceRepository;
    @Mock
    private ServicePriceRepository priceRepository;
    @Mock
    private ServicePriceBook priceBook;
    @Mock
    private LabTestItemRepository labTestItemRepository;
    @Mock
    private LabTestReferenceRangeRepository rangeRepository;
    @Mock
    private ServiceLabTestItemRepository serviceLabItemRepository;

    private CatalogServiceImpl catalog;

    @BeforeEach
    void setUp() {
        catalog = new CatalogServiceImpl(speciesRepository, categoryRepository, serviceRepository, priceRepository, priceBook,
                labTestItemRepository, rangeRepository, serviceLabItemRepository, mock(ClinicalMapper.class), CLOCK);
        lenient().when(labTestItemRepository.saveAndFlush(any(LabTestItem.class))).thenAnswer(inv -> {
            LabTestItem item = inv.getArgument(0);
            if (item.getId() == null) {
                item.setId((short) 10);
            }
            return item;
        });
        lenient().when(rangeRepository.findByLabTestItemIdIn(anyList())).thenReturn(List.of());
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static LabTestItem item(int id, String code) {
        return LabTestItem.builder().id((short) id).code(code).name(code).unit("g/dL").build();
    }

    // ================================================================== chỉ số xét nghiệm (UC-05, UC-06)

    @Test
    void createLabTestItem_normalizesCodeAndStoresRangesPerSpecies() {
        when(labTestItemRepository.existsByCode("HCT")).thenReturn(false);
        when(speciesRepository.findById((short) 1)).thenReturn(Optional.of(dog()));

        catalog.createLabTestItem(new LabTestItemRequest(" hct ", "Hematocrit", "%", null,
                List.of(new ReferenceRangeInput((short) 1, new BigDecimal("37.00"), new BigDecimal("55.00")))));

        ArgumentCaptor<LabTestItem> item = ArgumentCaptor.forClass(LabTestItem.class);
        verify(labTestItemRepository).saveAndFlush(item.capture());
        assertThat(item.getValue().getCode()).isEqualTo("HCT");
        ArgumentCaptor<LabTestReferenceRange> range = ArgumentCaptor.forClass(LabTestReferenceRange.class);
        verify(rangeRepository).save(range.capture());
        assertThat(range.getValue().getMinValue()).isEqualByComparingTo("37.00");
        // khóa ghép (chỉ số, loài) được đặt đúng
        assertThat(range.getValue().getId().getLabTestItemId()).isEqualTo((short) 10);
        assertThat(range.getValue().getId().getSpeciesId()).isEqualTo((short) 1);
    }

    @Test
    void createLabTestItem_refusesDuplicateCodeAndBadRanges() {
        when(labTestItemRepository.existsByCode("WBC")).thenReturn(true);
        assertError(ErrorCode.CODE_ALREADY_EXISTS,
                () -> catalog.createLabTestItem(new LabTestItemRequest("wbc", "Bạch cầu", "10^9/L", null, null)));

        // min >= max
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> catalog.createLabTestItem(new LabTestItemRequest("HCT", "Hct", "%", null,
                List.of(new ReferenceRangeInput((short) 1, new BigDecimal("55.00"), new BigDecimal("55.00"))))));
        // hai khoảng cho cùng một loài
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> catalog.createLabTestItem(new LabTestItemRequest("HCT", "Hct", "%", null,
                List.of(new ReferenceRangeInput((short) 1, new BigDecimal("1.00"), new BigDecimal("2.00")),
                        new ReferenceRangeInput((short) 1, new BigDecimal("3.00"), new BigDecimal("4.00"))))));
        verify(labTestItemRepository, never()).saveAndFlush(any());
    }

    @Test
    void createLabTestItem_unknownSpeciesIsRefused() {
        when(labTestItemRepository.existsByCode("HCT")).thenReturn(false);
        when(speciesRepository.findById((short) 99)).thenReturn(Optional.empty());

        assertError(ErrorCode.SPECIES_NOT_FOUND, () -> catalog.createLabTestItem(new LabTestItemRequest("HCT", "Hct", "%", null,
                List.of(new ReferenceRangeInput((short) 99, new BigDecimal("1.00"), new BigDecimal("2.00"))))));
    }

    @Test
    void updateLabTestItem_changesNameUnitDescriptionButNotTheCode() {
        LabTestItem wbc = item(1, "WBC");
        when(labTestItemRepository.findById((short) 1)).thenReturn(Optional.of(wbc));

        LabTestItemResponse response = catalog.updateLabTestItem((short) 1, new UpdateLabTestItemRequest("Bạch cầu", "10^9/L", "  Mô tả ", null));

        assertThat(response.code()).isEqualTo("WBC");
        assertThat(response.name()).isEqualTo("Bạch cầu");
        assertThat(response.description()).isEqualTo("Mô tả");
        assertThat(response.active()).isTrue();
        assertError(ErrorCode.LAB_ITEM_NOT_FOUND,
                () -> catalog.updateLabTestItem((short) 404, new UpdateLabTestItemRequest("x", "y", null, null)));
    }

    @Test
    void updateLabTestItem_canDeactivateWithoutDeleting() {
        LabTestItem wbc = item(1, "WBC");
        when(labTestItemRepository.findById((short) 1)).thenReturn(Optional.of(wbc));

        assertThat(catalog.updateLabTestItem((short) 1, new UpdateLabTestItemRequest("WBC", "g/dL", null, false)).active()).isFalse();
    }

    @Test
    void saveReferenceRange_updatesTheExistingRangeInsteadOfAddingASecond() {
        LabTestItem wbc = item(1, "WBC");
        LabTestReferenceRange existing = LabTestReferenceRange.builder().labTestItem(wbc).species(dog())
                .minValue(new BigDecimal("6.00")).maxValue(new BigDecimal("17.00")).build();
        when(labTestItemRepository.findById((short) 1)).thenReturn(Optional.of(wbc));
        when(speciesRepository.findById((short) 1)).thenReturn(Optional.of(dog()));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId((short) 1, (short) 1)).thenReturn(Optional.of(existing));

        catalog.saveReferenceRange((short) 1, (short) 1, new ReferenceRangeRequest(new BigDecimal("5.00"), new BigDecimal("18.00")));

        assertThat(existing.getMinValue()).isEqualByComparingTo("5.00");
        assertThat(existing.getMaxValue()).isEqualByComparingTo("18.00");
        verify(rangeRepository).saveAndFlush(existing);
    }

    @Test
    void saveReferenceRange_createsANewRangeWithTheCompositeKey() {
        LabTestItem wbc = item(1, "WBC");
        when(labTestItemRepository.findById((short) 1)).thenReturn(Optional.of(wbc));
        when(speciesRepository.findById((short) 1)).thenReturn(Optional.of(dog()));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId((short) 1, (short) 1)).thenReturn(Optional.empty());

        catalog.saveReferenceRange((short) 1, (short) 1, new ReferenceRangeRequest(new BigDecimal("6.00"), new BigDecimal("17.00")));

        ArgumentCaptor<LabTestReferenceRange> saved = ArgumentCaptor.forClass(LabTestReferenceRange.class);
        verify(rangeRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(new LabTestReferenceRange.Key((short) 1, (short) 1));
    }

    @Test
    void saveReferenceRange_minNotBelowMaxIsRefused() {
        assertError(ErrorCode.INVALID_INPUT_DATA,
                () -> catalog.saveReferenceRange((short) 1, (short) 1, new ReferenceRangeRequest(new BigDecimal("9.00"), new BigDecimal("3.00"))));
    }

    // ================================================================== chỉ số của dịch vụ

    @Test
    void setServiceLabTestItems_replacesTheMappingOfALaboratoryService() {
        when(serviceRepository.findById(sid(5))).thenReturn(Optional.of(service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00")));
        when(labTestItemRepository.findAllById(any())).thenReturn(List.of(item(1, "WBC"), item(4, "PLT")));
        when(serviceLabItemRepository.findItemIdsByServiceId(sid(5))).thenReturn(List.of((short) 1, (short) 4));
        when(labTestItemRepository.findAllByOrderByCodeAsc()).thenReturn(List.of(item(1, "WBC"), item(4, "PLT"), item(9, "GLU")));

        List<LabTestItemResponse> result = catalog.setServiceLabTestItems(sid(5), List.of((short) 1, (short) 4, (short) 4));

        verify(serviceLabItemRepository).deleteByServiceId(sid(5));
        ArgumentCaptor<List<ServiceLabTestItem>> saved = ArgumentCaptor.forClass(List.class);
        verify(serviceLabItemRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(ServiceLabTestItem::getLabTestItemId).containsExactly((short) 1, (short) 4);   // bỏ trùng
        assertThat(result).extracting(LabTestItemResponse::code).containsExactly("WBC", "PLT");
    }

    @Test
    void setServiceLabTestItems_refusesNonLabServicesAndUnknownItems() {
        when(serviceRepository.findById(sid(6))).thenReturn(Optional.of(service(6L, "IMG_US_AB", ServiceCategory.IMAGING, "200000.00")));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> catalog.setServiceLabTestItems(sid(6), List.of((short) 1)));

        when(serviceRepository.findById(sid(5))).thenReturn(Optional.of(service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00")));
        when(labTestItemRepository.findAllById(any())).thenReturn(List.of(item(1, "WBC")));
        assertError(ErrorCode.LAB_ITEM_NOT_FOUND, () -> catalog.setServiceLabTestItems(sid(5), List.of((short) 1, (short) 77)));
        verify(serviceLabItemRepository, never()).deleteByServiceId(any());
    }

    @Test
    void listServiceLabTestItems_unconfiguredServiceMeansEveryItem() {
        when(serviceRepository.findById(sid(5))).thenReturn(Optional.of(service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00")));
        when(serviceLabItemRepository.findItemIdsByServiceId(sid(5))).thenReturn(List.of());
        when(labTestItemRepository.findAllByOrderByCodeAsc()).thenReturn(List.of(item(1, "WBC"), item(9, "GLU")));

        assertThat(catalog.listServiceLabTestItems(sid(5))).hasSize(2);
    }

    // ================================================================== nhóm dịch vụ (FR-03)

    @Test
    void createCategory_defaultsPrepaymentFromTheQueue() {
        when(categoryRepository.existsByCode("DENTAL")).thenReturn(false);
        when(categoryRepository.existsByCode("RADIO")).thenReturn(false);
        when(categoryRepository.saveAndFlush(any(ServiceCategory.class))).thenAnswer(inv -> inv.getArgument(0));

        ServiceCategoryResponse inRoom = catalog.createCategory(new ServiceCategoryRequest(" dental ", "Nha khoa", null, null, (short) 10, null));
        ServiceCategoryResponse queued = catalog.createCategory(new ServiceCategoryRequest("radio", "Xạ hình", "IMAGING", null, null, null));

        assertThat(inRoom.code()).isEqualTo("DENTAL");
        assertThat(inRoom.queueType()).isNull();
        assertThat(inRoom.requiresPrepayment()).isFalse();   // làm tại phòng: thu khi chốt ca
        assertThat(queued.requiresPrepayment()).isTrue();    // vào hàng đợi: thu trước
        assertThat(queued.active()).isTrue();
    }

    @Test
    void createCategory_refusesDuplicateCode() {
        when(categoryRepository.existsByCode("LABORATORY")).thenReturn(true);

        assertError(ErrorCode.CODE_ALREADY_EXISTS,
                () -> catalog.createCategory(new ServiceCategoryRequest("laboratory", "XN", "LABORATORY", true, null, null)));
    }

    @Test
    void updateCategory_changesPolicyButNotTheCode() {
        ServiceCategory vaccination = category("VACCINATION");
        when(categoryRepository.findById((short) 2)).thenReturn(Optional.of(vaccination));
        when(categoryRepository.saveAndFlush(any(ServiceCategory.class))).thenAnswer(inv -> inv.getArgument(0));

        ServiceCategoryResponse response = catalog.updateCategory((short) 2,
                new ServiceCategoryRequest("KHAC", "Tiêm chủng", null, true, (short) 3, false));

        assertThat(response.code()).isEqualTo("VACCINATION");   // mã bất biến
        assertThat(response.name()).isEqualTo("Tiêm chủng");
        assertThat(response.requiresPrepayment()).isTrue();
        assertThat(response.displayOrder()).isEqualTo((short) 3);
        assertThat(response.active()).isFalse();
    }

    // ================================================================== dịch vụ + bảng giá

    @Test
    void createService_savesTheServiceAndOpensItsFirstPriceInTheSameTransaction() {
        loginAs(ADMIN_ID_FOR_PRICES, com.vetclinic.common.security.RoleCode.ADMIN);
        when(serviceRepository.existsByCode("VAC_5B")).thenReturn(false);
        when(categoryRepository.findByCode("VACCINATION")).thenReturn(Optional.of(category("VACCINATION")));
        when(serviceRepository.saveAndFlush(any(ClinicService.class))).thenAnswer(inv -> {
            ClinicService s = inv.getArgument(0);
            s.setId(sid(77));
            return s;
        });
        ServicePrice first = ServicePrice.builder().price(new BigDecimal("180000.00")).vatRate(new BigDecimal("0.05"))
                .effectiveFrom(Instant.parse("2026-10-30T02:00:00Z")).build();
        when(priceBook.openFirstPrice(any(ClinicService.class), eq(new BigDecimal("180000.00")), eq(new BigDecimal("0.05")), any(), eq(ADMIN_ID_FOR_PRICES)))
                .thenReturn(first);

        ServiceResponse response = catalog.createService(new CreateServiceRequest(" vac_5b ", " Vaccine 5 bệnh ", "vaccination",
                new BigDecimal("180000.00"), new BigDecimal("0.05"), null, (short) 10, null, null));

        assertThat(response.code()).isEqualTo("VAC_5B");
        assertThat(response.category()).isEqualTo("VACCINATION");
        assertThat(response.unit()).isEqualTo("Lần");   // mặc định
        assertThat(response.requiresPrepayment()).isFalse();   // theo nhóm
        assertThat(response.price()).isEqualByComparingTo("180000.00");
        assertThat(response.vatRate()).isEqualByComparingTo("0.05");
    }

    @Test
    void createService_refusesDuplicateCodeAndUnknownOrInactiveCategory() {
        when(serviceRepository.existsByCode("EXAM_GEN")).thenReturn(true);
        assertError(ErrorCode.CODE_ALREADY_EXISTS, () -> catalog.createService(new CreateServiceRequest("exam_gen", "Khám", "EXAMINATION",
                new BigDecimal("1.00"), null, null, null, null, null)));

        when(serviceRepository.existsByCode("X1")).thenReturn(false);
        when(categoryRepository.findByCode("NOPE")).thenReturn(Optional.empty());
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> catalog.createService(new CreateServiceRequest("x1", "X", "nope",
                new BigDecimal("1.00"), null, null, null, null, null)));

        ServiceCategory inactive = category("SURGERY");
        inactive.setActive(false);
        when(categoryRepository.findByCode("SURGERY")).thenReturn(Optional.of(inactive));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> catalog.createService(new CreateServiceRequest("x1", "X", "surgery",
                new BigDecimal("1.00"), null, null, null, null, null)));
        verify(serviceRepository, never()).saveAndFlush(any());
    }

    @Test
    void listServices_attachesTheCurrentPriceOfEachServiceWithOneQuery() {
        ClinicService exam = service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00");
        ClinicService cbc = service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00");
        when(serviceRepository.search(true, "LABORATORY")).thenReturn(List.of(exam, cbc));
        ServicePrice examPrice = ServicePrice.builder().service(exam).price(new BigDecimal("100000.00")).vatRate(new BigDecimal("0.00"))
                .effectiveFrom(Instant.parse("2026-01-01T00:00:00Z")).build();
        when(priceRepository.findEffectiveForServices(anyList(), eq(CLOCK.instant()))).thenReturn(List.of(examPrice));

        List<ServiceResponse> result = catalog.listServices(true, "LABORATORY");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).price()).isEqualByComparingTo("100000.00");
        assertThat(result.get(1).price()).isNull();   // chưa có giá hiệu lực: giao diện báo "chưa có giá"
        assertThat(result.get(1).queueType()).isEqualTo("LABORATORY");
        verify(priceRepository).findEffectiveForServices(anyList(), any());
    }

    @Test
    void updateService_neverTouchesThePrice_andCanDeactivate() {
        ClinicService exam = service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00");
        when(serviceRepository.findById(sid(1))).thenReturn(Optional.of(exam));
        when(priceRepository.findEffective(sid(1), CLOCK.instant())).thenReturn(Optional.empty());

        ServiceResponse response = catalog.updateService(sid(1), new UpdateServiceRequest(" Khám tổng quát ", "Ca", (short) 25, "Nhịn ăn", null, false));

        assertThat(response.name()).isEqualTo("Khám tổng quát");
        assertThat(response.unit()).isEqualTo("Ca");
        assertThat(response.estimatedMinutes()).isEqualTo((short) 25);
        assertThat(response.preparationNotes()).isEqualTo("Nhịn ăn");
        assertThat(response.active()).isFalse();
        verify(priceBook, never()).changePrice(any(), any(), any(), any(), any(), any());
    }

    @Test
    void changePrice_delegatesToThePriceBookAndAppliesImmediatelyWhenNoDateIsGiven() {
        loginAs(ADMIN_ID_FOR_PRICES, com.vetclinic.common.security.RoleCode.ADMIN);
        ClinicService exam = service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00");
        when(serviceRepository.findById(sid(1))).thenReturn(Optional.of(exam));
        ServicePrice created = ServicePrice.builder().id(9L).price(new BigDecimal("120000.00")).vatRate(new BigDecimal("0.00"))
                .effectiveFrom(CLOCK.instant()).changeReason("Điều chỉnh giá").createdBy(ADMIN_ID_FOR_PRICES).build();
        when(priceBook.changePrice(exam, new BigDecimal("120000.00"), new BigDecimal("0.00"), CLOCK.instant(), "Điều chỉnh giá", ADMIN_ID_FOR_PRICES))
                .thenReturn(created);

        ServicePriceResponse response = catalog.changePrice(sid(1),
                new ChangePriceRequest(new BigDecimal("120000.00"), new BigDecimal("0.00"), null, "Điều chỉnh giá"));

        assertThat(response.price()).isEqualByComparingTo("120000.00");
        assertThat(response.current()).isTrue();
    }

    @Test
    void changePrice_aFuturePriceIsNotCurrentYet() {
        loginAs(ADMIN_ID_FOR_PRICES, com.vetclinic.common.security.RoleCode.ADMIN);
        ClinicService exam = service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00");
        when(serviceRepository.findById(sid(1))).thenReturn(Optional.of(exam));
        Instant tomorrow = CLOCK.instant().plusSeconds(86400);
        ServicePrice created = ServicePrice.builder().price(new BigDecimal("130000.00")).vatRate(new BigDecimal("0.00"))
                .effectiveFrom(tomorrow).createdBy(ADMIN_ID_FOR_PRICES).build();
        when(priceBook.changePrice(any(), any(), any(), eq(tomorrow), any(), any())).thenReturn(created);

        ServicePriceResponse response = catalog.changePrice(sid(1),
                new ChangePriceRequest(new BigDecimal("130000.00"), new BigDecimal("0.00"), tomorrow, null));

        assertThat(response.current()).isFalse();
    }

    @Test
    void listPrices_marksOnlyTheCurrentRow() {
        ClinicService exam = service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00");
        when(serviceRepository.findById(sid(1))).thenReturn(Optional.of(exam));
        Instant now = CLOCK.instant();
        ServicePrice future = ServicePrice.builder().price(new BigDecimal("130000.00")).vatRate(new BigDecimal("0.00")).effectiveFrom(now.plusSeconds(3600)).build();
        ServicePrice current = ServicePrice.builder().price(new BigDecimal("120000.00")).vatRate(new BigDecimal("0.00"))
                .effectiveFrom(now.minusSeconds(3600)).effectiveTo(now.plusSeconds(3600)).build();
        ServicePrice old = ServicePrice.builder().price(new BigDecimal("100000.00")).vatRate(new BigDecimal("0.00"))
                .effectiveFrom(now.minusSeconds(86400)).effectiveTo(now.minusSeconds(3600)).build();
        when(priceRepository.findByServiceIdOrderByEffectiveFromDesc(sid(1))).thenReturn(List.of(future, current, old));

        List<ServicePriceResponse> rows = catalog.listPrices(sid(1));

        assertThat(rows).extracting(ServicePriceResponse::current).containsExactly(false, true, false);
    }

    @Test
    void unknownService_isReportedAsNotFound() {
        UUID missing = sid(404);
        when(serviceRepository.findById(missing)).thenReturn(Optional.empty());

        assertError(ErrorCode.SERVICE_NOT_FOUND, () -> catalog.getService(missing));
        assertError(ErrorCode.SERVICE_NOT_FOUND, () -> catalog.listPrices(missing));
    }
}
