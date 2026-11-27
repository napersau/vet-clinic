package com.vetclinic.billing.service.impl;

import com.vetclinic.billing.constant.InventoryTransactionType;
import com.vetclinic.billing.dto.request.ChangePriceRequest;
import com.vetclinic.billing.dto.request.IngredientInput;
import com.vetclinic.billing.dto.request.InventoryAdjustRequest;
import com.vetclinic.billing.dto.request.InventoryImportRequest;
import com.vetclinic.billing.dto.request.MedicineCategoryRequest;
import com.vetclinic.billing.dto.request.MedicineRequest;
import com.vetclinic.billing.dto.request.UpdateMedicineRequest;
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
import com.vetclinic.billing.repository.MedicineStockRepository;
import com.vetclinic.billing.service.BillingAssembler;
import com.vetclinic.billing.service.MedicinePriceBook;
import com.vetclinic.billing.service.StockAlertPublisher;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import jakarta.persistence.EntityManager;
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

import static com.vetclinic.billing.testsupport.TestData.CLOCK;
import static com.vetclinic.billing.testsupport.TestData.DOCTOR;
import static com.vetclinic.billing.testsupport.TestData.assertError;
import static com.vetclinic.billing.testsupport.TestData.loginAs;
import static com.vetclinic.billing.testsupport.TestData.medicine;
import static com.vetclinic.billing.testsupport.TestData.mid;
import static com.vetclinic.billing.testsupport.TestData.setStock;
import static com.vetclinic.billing.testsupport.TestData.uid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MedicineServiceImplTest {

    private static final UUID ADMIN = uid(1);
    private static final MedicineCategory ANTIBIOTIC = MedicineCategory.builder().id((short) 1).code("ANTIBIOTIC").name("Kháng sinh").build();

    @Mock
    private MedicineRepository medicineRepository;
    @Mock
    private MedicineCategoryRepository categoryRepository;
    @Mock
    private MedicineStockRepository stockRepository;
    @Mock
    private MedicinePriceRepository priceRepository;
    @Mock
    private MedicinePriceBook priceBook;
    @Mock
    private InventoryTransactionRepository inventoryRepository;
    @Mock
    private StockAlertPublisher stockAlerts;
    @Mock
    private EntityManager entityManager;

    private MedicineServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MedicineServiceImpl(medicineRepository, categoryRepository, stockRepository, priceRepository, priceBook,
                inventoryRepository, new BillingAssembler(), stockAlerts, entityManager, CLOCK);
        lenient().when(medicineRepository.saveAndFlush(any(Medicine.class))).thenAnswer(inv -> {
            Medicine medicine = inv.getArgument(0);
            if (medicine.getId() == null) {
                medicine.setId(mid(77L));
            }
            return medicine;
        });
        lenient().when(stockRepository.saveAndFlush(any(MedicineStock.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(categoryRepository.findByCode("ANTIBIOTIC")).thenReturn(Optional.of(ANTIBIOTIC));
        lenient().when(priceRepository.findEffective(any(UUID.class), any(Instant.class))).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private void stubFind(Medicine medicine) {
        when(medicineRepository.findWithCategoryAndStockById(medicine.getId())).thenReturn(Optional.of(medicine));
    }

    /** Mô phỏng UPDATE nguyên tử rồi refresh(): sau refresh entity mang tồn kho thật. */
    private void stockBecomes(Medicine medicine, int newStock) {
        lenient().doAnswer(inv -> {
            setStock(medicine, newStock);
            return null;
        }).when(entityManager).refresh(medicine);
    }

    // ================================================================== kho

    @Test
    void importStock_increasesStockAndLogsPositiveImportWithUnitCost() {
        loginAs(ADMIN, RoleCode.ADMIN);
        Medicine medicine = medicine(1L, "Clavamox", "15000", 20);
        stubFind(medicine);
        when(stockRepository.add(mid(1L), 100)).thenReturn(1);
        stockBecomes(medicine, 120);

        MedicineResponse response = service.importStock(mid(1L), new InventoryImportRequest(100, new BigDecimal("9000"), "Nhập lô tháng 10"));

        assertThat(response.stockQuantity()).isEqualTo(120);
        ArgumentCaptor<InventoryTransaction> log = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryRepository).save(log.capture());
        assertThat(log.getValue().getTransactionType()).isEqualTo(InventoryTransactionType.IMPORT);
        assertThat(log.getValue().getQuantity()).isEqualTo(100);
        assertThat(log.getValue().getUnitCost()).isEqualByComparingTo("9000");   // giá nhập chỉ ghi ở IMPORT
        assertThat(log.getValue().getCreatedBy()).isEqualTo(ADMIN);
        verify(stockAlerts, never()).publishIfLow(any(), any());
    }

    @Test
    void adjustStock_negativeDelta_logsSignedAdjustmentAndChecksLowStockAlert() {
        loginAs(ADMIN, RoleCode.ADMIN);
        Medicine medicine = medicine(1L, "Clavamox", "15000", 20);
        stubFind(medicine);
        when(stockRepository.add(mid(1L), -12)).thenReturn(1);
        stockBecomes(medicine, 8);

        service.adjustStock(mid(1L), new InventoryAdjustRequest(-12, "Hết hạn sử dụng"));

        ArgumentCaptor<InventoryTransaction> log = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryRepository).save(log.capture());
        assertThat(log.getValue().getTransactionType()).isEqualTo(InventoryTransactionType.ADJUSTMENT);
        assertThat(log.getValue().getQuantity()).isEqualTo(-12);   // quantity có dấu
        assertThat(log.getValue().getNotes()).isEqualTo("Hết hạn sử dụng");
        verify(stockAlerts).publishIfLow(medicine, ADMIN);
    }

    @Test
    void adjustStock_positiveDelta_doesNotRaiseALowStockAlert() {
        loginAs(ADMIN, RoleCode.ADMIN);
        Medicine medicine = medicine(1L, "Clavamox", "15000", 5);
        stubFind(medicine);
        when(stockRepository.add(mid(1L), 30)).thenReturn(1);
        stockBecomes(medicine, 35);

        service.adjustStock(mid(1L), new InventoryAdjustRequest(30, "Kiểm kê thừa"));

        verify(stockAlerts, never()).publishIfLow(any(), any());
    }

    @Test
    void adjustStock_thatWouldMakeStockNegative_isRefusedAndNothingIsLogged() {
        loginAs(ADMIN, RoleCode.ADMIN);
        stubFind(medicine(1L, "Clavamox", "15000", 5));
        when(stockRepository.add(mid(1L), -50)).thenReturn(0);   // điều kiện quantity_on_hand + delta >= 0 không thỏa

        assertError(ErrorCode.INVALID_STOCK_ADJUSTMENT, () -> service.adjustStock(mid(1L), new InventoryAdjustRequest(-50, "Mất")));
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    void adjustStock_zeroDeltaIsRefused() {
        loginAs(ADMIN, RoleCode.ADMIN);

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.adjustStock(mid(1L), new InventoryAdjustRequest(0, "Không đổi")));
        verify(stockRepository, never()).add(any(UUID.class), anyInt());
    }

    // ================================================================== thêm / sửa

    @Test
    void create_normalizesCode_opensTheFirstPrice_createsTheStockRow_andLogsOpeningStock() {
        loginAs(ADMIN, RoleCode.ADMIN);
        when(medicineRepository.existsByCodeIgnoreCase("MED_NEW")).thenReturn(false);
        when(priceBook.openFirstPrice(any(Medicine.class), any(), any(), any(), eq(ADMIN))).thenAnswer(inv -> MedicinePrice.builder()
                .medicine(inv.getArgument(0)).salePrice(inv.getArgument(1)).vatRate(inv.getArgument(2)).effectiveFrom(CLOCK.instant()).build());

        MedicineResponse response = service.create(new MedicineRequest("ANTIBIOTIC", " med_new ", "Thuốc mới", "Viên nén", "250mg", "Viên",
                "Hộp 10 vỉ x 10 viên", "Zoetis", "Hoa Kỳ", null, null, null, null,
                List.of(new IngredientInput("Amoxicillin", new BigDecimal("200"), "mg"), new IngredientInput("Clavulanic acid", new BigDecimal("50"), "mg")),
                new BigDecimal("2500"), null, 40));

        assertThat(response.code()).isEqualTo("MED_NEW");
        assertThat(response.categoryCode()).isEqualTo("ANTIBIOTIC");
        assertThat(response.baseUnit()).isEqualTo("Viên");
        assertThat(response.packagingSpec()).isEqualTo("Hộp 10 vỉ x 10 viên");
        assertThat(response.ingredients()).extracting(i -> i.ingredientName()).containsExactly("Amoxicillin", "Clavulanic acid");
        assertThat(response.requiresPrescription()).isTrue();   // mặc định
        assertThat(response.minStockAlert()).isEqualTo(10);     // mặc định
        assertThat(response.salePrice()).isEqualByComparingTo("2500");
        assertThat(response.vatRate()).isEqualByComparingTo("0.05");   // mặc định 5%
        assertThat(response.stockQuantity()).isEqualTo(40);
        ArgumentCaptor<MedicineStock> stock = ArgumentCaptor.forClass(MedicineStock.class);
        verify(stockRepository).saveAndFlush(stock.capture());
        assertThat(stock.getValue().getQuantityOnHand()).isEqualTo(40);
        ArgumentCaptor<InventoryTransaction> log = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryRepository).save(log.capture());
        assertThat(log.getValue().getTransactionType()).isEqualTo(InventoryTransactionType.IMPORT);
        assertThat(log.getValue().getQuantity()).isEqualTo(40);
    }

    @Test
    void create_duplicateCode_unknownCategory_orRepeatedIngredient_areRefused() {
        loginAs(ADMIN, RoleCode.ADMIN);
        when(medicineRepository.existsByCodeIgnoreCase("MED_AMOX")).thenReturn(true);
        assertError(ErrorCode.MEDICINE_CODE_ALREADY_EXISTS, () -> service.create(new MedicineRequest("ANTIBIOTIC", "med_amox", "Trùng", "Viên", null,
                "Viên", "Hộp", null, null, null, null, null, null, null, BigDecimal.TEN, null, null)));

        when(categoryRepository.findByCode("KHONG_CO")).thenReturn(Optional.empty());
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.create(new MedicineRequest("KHONG_CO", "MED_X", "X", "Viên", null,
                "Viên", "Hộp", null, null, null, null, null, null, null, BigDecimal.TEN, null, null)));

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.create(new MedicineRequest("ANTIBIOTIC", "MED_Y", "Y", "Viên", null,
                "Viên", "Hộp", null, null, null, null, null, null,
                List.of(new IngredientInput("Amoxicillin", null, null), new IngredientInput(" amoxicillin ", null, null)),
                BigDecimal.TEN, null, null)));
        verify(medicineRepository, never()).saveAndFlush(any());
        verify(priceBook, never()).openFirstPrice(any(), any(), any(), any(), any());
    }

    @Test
    void update_canDeactivateAndReplaceIngredientsByName_butNeverTouchesStockOrPrices() {
        loginAs(ADMIN, RoleCode.ADMIN);
        Medicine medicine = medicine(1L, "Clavamox", "15000", 20);
        MedicineIngredient amox = MedicineIngredient.builder().id(1L).ingredientName("Amoxicillin").strengthValue(new BigDecimal("200")).strengthUnit("mg").build();
        MedicineIngredient old = MedicineIngredient.builder().id(2L).ingredientName("Hoạt chất cũ").build();
        medicine.addIngredient(amox);
        medicine.addIngredient(old);
        stubFind(medicine);

        MedicineResponse response = service.update(mid(1L), new UpdateMedicineRequest(null, "Clavamox 500", "Viên nén", "500mg", "Viên",
                "Hộp 2 vỉ", null, null, null, null, null, null,
                List.of(new IngredientInput("amoxicillin", new BigDecimal("400"), "mg"), new IngredientInput("Clavulanic acid", new BigDecimal("100"), "mg")), false));

        assertThat(response.active()).isFalse();
        assertThat(response.name()).isEqualTo("Clavamox 500");
        assertThat(response.stockQuantity()).isEqualTo(20);
        assertThat(response.minStockAlert()).isEqualTo(10);   // bỏ trống = giữ nguyên
        // thành phần thay theo TÊN: dòng "Amoxicillin" được sửa tại chỗ (giữ id, tránh INSERT trước DELETE vi phạm UNIQUE), dòng cũ bị bỏ, dòng mới được thêm
        assertThat(medicine.getIngredients()).extracting(MedicineIngredient::getIngredientName).containsExactly("amoxicillin", "Clavulanic acid");
        assertThat(medicine.getIngredients().get(0).getId()).isEqualTo(1L);
        assertThat(medicine.getIngredients().get(0).getStrengthValue()).isEqualByComparingTo("400");
        verify(stockRepository, never()).add(any(UUID.class), anyInt());
        verify(stockRepository, never()).deduct(any(UUID.class), anyInt());
        verify(priceBook, never()).changePrice(any(), any(), any(), any(), any(), any());
    }

    // ================================================================== bảng giá

    @Test
    void changePrice_goesThroughThePriceBook_notThroughTheMedicineRow() {
        loginAs(ADMIN, RoleCode.ADMIN);
        Medicine medicine = medicine(1L, "Clavamox", "15000", 20);
        stubFind(medicine);
        Instant tomorrow = CLOCK.instant().plusSeconds(86_400);
        when(priceBook.changePrice(eq(medicine), any(), any(), eq(tomorrow), eq("Nhà cung cấp tăng giá"), eq(ADMIN))).thenAnswer(inv -> MedicinePrice.builder()
                .id(9L).medicine(medicine).salePrice(inv.getArgument(1)).vatRate(inv.getArgument(2)).effectiveFrom(tomorrow).createdBy(ADMIN).build());

        var response = service.changePrice(mid(1L), new ChangePriceRequest(new BigDecimal("18000"), new BigDecimal("0.08"), tomorrow, "Nhà cung cấp tăng giá"));

        assertThat(response.salePrice()).isEqualByComparingTo("18000");
        assertThat(response.vatRate()).isEqualByComparingTo("0.08");
        assertThat(response.current()).isFalse();   // hiệu lực ngày mai: giá hiện hành vẫn là giá cũ
    }

    @Test
    void prices_returnsTheHistoryNewestFirst_andFlagsTheCurrentRow() {
        loginAs(ADMIN, RoleCode.ADMIN);
        Medicine medicine = medicine(1L, "Clavamox", "15000", 20);
        stubFind(medicine);
        Instant now = CLOCK.instant();
        MedicinePrice future = MedicinePrice.builder().id(3L).medicine(medicine).salePrice(new BigDecimal("18000")).vatRate(new BigDecimal("0.05")).effectiveFrom(now.plusSeconds(3600)).build();
        MedicinePrice current = MedicinePrice.builder().id(2L).medicine(medicine).salePrice(new BigDecimal("15000")).vatRate(new BigDecimal("0.05")).effectiveFrom(now.minusSeconds(3600)).effectiveTo(now.plusSeconds(3600)).build();
        MedicinePrice past = MedicinePrice.builder().id(1L).medicine(medicine).salePrice(new BigDecimal("12000")).vatRate(new BigDecimal("0.05")).effectiveFrom(now.minusSeconds(7200)).effectiveTo(now.minusSeconds(3600)).build();
        when(priceRepository.findByMedicineIdOrderByEffectiveFromDesc(mid(1L))).thenReturn(List.of(future, current, past));

        var history = service.prices(mid(1L));

        assertThat(history).extracting(p -> p.id()).containsExactly(3L, 2L, 1L);
        assertThat(history).extracting(p -> p.current()).containsExactly(false, true, false);
    }

    // ================================================================== tra cứu

    @Test
    void getById_inactiveMedicineIsHiddenFromNonAdmins() {
        Medicine inactive = medicine(1L, "Thuốc cũ", "1000", 5);
        inactive.setActive(false);
        stubFind(inactive);

        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        assertError(ErrorCode.MEDICINE_NOT_FOUND, () -> service.getById(mid(1L)));

        loginAs(ADMIN, RoleCode.ADMIN);
        assertThat(service.getById(mid(1L)).active()).isFalse();
    }

    @Test
    void getById_carriesTheCurrentPriceAndTheStock() {
        loginAs(ADMIN, RoleCode.ADMIN);
        Medicine medicine = medicine(1L, "Clavamox", "15000", 8);
        stubFind(medicine);
        when(priceRepository.findEffective(eq(mid(1L)), any(Instant.class))).thenReturn(Optional.of(MedicinePrice.builder().medicine(medicine)
                .salePrice(new BigDecimal("15000")).vatRate(new BigDecimal("0.05")).effectiveFrom(CLOCK.instant().minusSeconds(60)).build()));

        MedicineResponse response = service.getById(mid(1L));

        assertThat(response.salePrice()).isEqualByComparingTo("15000");
        assertThat(response.stockQuantity()).isEqualTo(8);
        assertThat(response.lowStock()).isTrue();   // 8 <= ngưỡng 10
    }

    @Test
    void getById_unknownMedicine() {
        loginAs(ADMIN, RoleCode.ADMIN);
        when(medicineRepository.findWithCategoryAndStockById(mid(404L))).thenReturn(Optional.empty());

        assertError(ErrorCode.MEDICINE_NOT_FOUND, () -> service.getById(mid(404L)));
    }

    // ================================================================== nhóm thuốc

    @Test
    void categories_adminSeesInactiveOnesOnRequest_othersOnlyActive() {
        MedicineCategory hidden = MedicineCategory.builder().id((short) 9).code("OLD").name("Cũ").active(false).build();
        when(categoryRepository.findAllByOrderByNameAsc()).thenReturn(List.of(ANTIBIOTIC, hidden));
        when(categoryRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(ANTIBIOTIC));

        loginAs(ADMIN, RoleCode.ADMIN);
        assertThat(service.categories(true)).hasSize(2);
        assertThat(service.categories(false)).hasSize(1);
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        assertThat(service.categories(true)).hasSize(1);   // không phải Admin: luôn chỉ nhóm đang dùng
    }

    @Test
    void createCategory_normalizesTheCode_andRefusesDuplicates() {
        loginAs(ADMIN, RoleCode.ADMIN);
        when(categoryRepository.existsByCodeIgnoreCase("VITAMIN")).thenReturn(false);
        when(categoryRepository.existsByCodeIgnoreCase("ANTIBIOTIC")).thenReturn(true);
        when(categoryRepository.saveAndFlush(any(MedicineCategory.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.createCategory(new MedicineCategoryRequest(" vitamin ", "Vitamin", null)).code()).isEqualTo("VITAMIN");
        assertError(ErrorCode.CODE_ALREADY_EXISTS, () -> service.createCategory(new MedicineCategoryRequest("antibiotic", "Trùng", null)));
    }
}
