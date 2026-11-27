package com.vetclinic.billing.service.impl;

import com.vetclinic.billing.constant.InventoryTransactionType;
import com.vetclinic.billing.constant.PaymentStatus;
import com.vetclinic.billing.dto.request.MedicineInvoiceRequest;
import com.vetclinic.billing.dto.response.MedicineInvoiceResponse;
import com.vetclinic.billing.dto.response.MedicineInvoiceStatusResponse;
import com.vetclinic.billing.entity.InventoryTransaction;
import com.vetclinic.billing.entity.Medicine;
import com.vetclinic.billing.entity.MedicineInvoice;
import com.vetclinic.billing.entity.MedicineInvoiceItem;
import com.vetclinic.billing.repository.InventoryTransactionRepository;
import com.vetclinic.billing.repository.MedicineInvoiceRepository;
import com.vetclinic.billing.repository.MedicineRepository;
import com.vetclinic.billing.repository.MedicineStockRepository;
import com.vetclinic.billing.service.BillingAssembler;
import com.vetclinic.billing.service.MedicinePriceBook;
import com.vetclinic.billing.service.StockAlertPublisher;
import com.vetclinic.billing.testsupport.TestData;
import com.vetclinic.common.event.DomainEvent;
import com.vetclinic.common.event.MedicineInvoicePaidEvent;
import com.vetclinic.common.event.PrescriptionCreatedEvent;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.vetclinic.billing.testsupport.TestData.CLOCK;
import static com.vetclinic.billing.testsupport.TestData.DOCTOR;
import static com.vetclinic.billing.testsupport.TestData.RECEPTIONIST;
import static com.vetclinic.billing.testsupport.TestData.assertError;
import static com.vetclinic.billing.testsupport.TestData.loginAs;
import static com.vetclinic.billing.testsupport.TestData.medicine;
import static com.vetclinic.billing.testsupport.TestData.mid;
import static com.vetclinic.billing.testsupport.TestData.pid;
import static com.vetclinic.billing.testsupport.TestData.uid;
import static com.vetclinic.billing.testsupport.TestData.vid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MedicineInvoiceServiceImplTest {

    private static final UUID INVOICE = uid(500);
    private static final UUID PRESCRIPTION = pid(3);
    private static final UUID VISIT = vid(7);

    @Mock
    private MedicineInvoiceRepository invoiceRepository;
    @Mock
    private MedicineRepository medicineRepository;
    @Mock
    private MedicineStockRepository stockRepository;
    @Mock
    private InventoryTransactionRepository inventoryRepository;
    @Mock
    private StockAlertPublisher stockAlerts;
    @Mock
    private OutboxWriter outboxWriter;
    @Mock
    private EntityManager entityManager;

    private MedicineInvoiceServiceImpl service;

    /** "CSDL" giả của kho: deduct trừ thật vào đây và refresh() đọc lại từ đây, mô phỏng UPDATE nguyên tử. */
    private final Map<UUID, Integer> stockDb = new HashMap<>();
    /** Danh mục thuốc thử (để getReferenceById trả đúng thực thể). */
    private final Map<UUID, Medicine> catalog = new HashMap<>();
    private final MedicinePriceBook priceBook = TestData.priceBook();

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(stockRepository.deduct(any(UUID.class), anyInt())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            int quantity = inv.getArgument(1);
            if (stockDb.get(id) < quantity) {
                return 0;
            }
            stockDb.merge(id, -quantity, Integer::sum);
            return 1;
        });
        lenient().doAnswer(inv -> {
            Medicine medicine = inv.getArgument(0);
            TestData.setStock(medicine, stockDb.get(medicine.getId()));
            return null;
        }).when(entityManager).refresh(any(Medicine.class));
        lenient().when(medicineRepository.getReferenceById(any(UUID.class))).thenAnswer(inv -> catalog.get((UUID) inv.getArgument(0)));
        lenient().when(invoiceRepository.saveAndFlush(any(MedicineInvoice.class))).thenAnswer(inv -> {
            MedicineInvoice invoice = inv.getArgument(0);
            if (invoice.getId() == null) {
                invoice.setId(INVOICE);
            }
            return invoice;
        });
        service = new MedicineInvoiceServiceImpl(invoiceRepository, medicineRepository, stockRepository, priceBook,
                inventoryRepository, new BillingAssembler(), stockAlerts, outboxWriter, entityManager, CLOCK, tm);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private Medicine amox() {
        return catalog.computeIfAbsent(mid(1L), k -> medicine(1L, "Clavamox", "15000", 200));
    }

    private Medicine drontal() {
        return catalog.computeIfAbsent(mid(2L), k -> medicine(2L, "Drontal", "45000", 100));
    }

    private static MedicineInvoiceRequest buy(String discount, MedicineInvoiceRequest.Line... lines) {
        return new MedicineInvoiceRequest(List.of(lines), discount == null ? null : new BigDecimal(discount));
    }

    private static MedicineInvoiceRequest.Line line(long medicineNo, int quantity) {
        return new MedicineInvoiceRequest.Line(mid(medicineNo), quantity);
    }

    private PrescriptionCreatedEvent event(PrescriptionCreatedEvent.Item... items) {
        return new PrescriptionCreatedEvent(uid(900), CLOCK.instant(), DOCTOR, PRESCRIPTION, VISIT, "Nguyễn Văn A", "0900000001", "Lu", "BS Tiến",
                List.of(items));
    }

    private static PrescriptionCreatedEvent.Item rx(long medicineNo, int quantity) {
        return new PrescriptionCreatedEvent.Item(mid(medicineNo), quantity);
    }

    /** Hóa đơn PENDING đang mua cả hai dòng của đơn chuẩn (10 Clavamox + 2 Drontal), giá snapshot lúc tạo nháp. */
    private MedicineInvoice pendingInvoice(int amoxQuantity, int drontalQuantity) {
        MedicineInvoice invoice = MedicineInvoice.builder().id(INVOICE).invoiceCode("MINV-2026-ABC234").prescriptionId(PRESCRIPTION).visitId(VISIT)
                .customerNameSnapshot("Nguyễn Văn A").customerPhoneSnapshot("0900000001").petNameSnapshot("Lu").doctorNameSnapshot("BS Tiến").build();
        invoice.addItem(MedicineInvoiceItem.builder().id(100L).medicine(amox()).prescribedQuantity(10).quantity(amoxQuantity)
                .unitPrice(new BigDecimal("15000")).vatRate(new BigDecimal("0.05")).build());
        if (drontalQuantity > 0) {
            invoice.addItem(MedicineInvoiceItem.builder().id(101L).medicine(drontal()).prescribedQuantity(2).quantity(drontalQuantity)
                    .unitPrice(new BigDecimal("45000")).vatRate(new BigDecimal("0.05")).build());
        }
        return invoice;
    }

    // ================================================================== nháp từ PrescriptionCreatedEvent

    @Test
    void createDraft_buildsPendingInvoiceWithSnapshotsAndDefaultQuantityEqualToPrescribed() {
        amox();
        drontal();
        when(invoiceRepository.findByPrescriptionIdForUpdate(PRESCRIPTION)).thenReturn(Optional.empty());

        service.createDraft(event(rx(1, 10), rx(2, 2)));

        ArgumentCaptor<MedicineInvoice> saved = ArgumentCaptor.forClass(MedicineInvoice.class);
        verify(invoiceRepository).saveAndFlush(saved.capture());
        MedicineInvoice invoice = saved.getValue();
        assertThat(invoice.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(invoice.getInvoiceCode()).startsWith("MINV-2026-");   // năm 2026 (CLOCK)
        assertThat(invoice.getPrescriptionId()).isEqualTo(PRESCRIPTION);
        assertThat(invoice.getVisitId()).isEqualTo(VISIT);
        assertThat(invoice.getCustomerNameSnapshot()).isEqualTo("Nguyễn Văn A");
        assertThat(invoice.getPetNameSnapshot()).isEqualTo("Lu");
        assertThat(invoice.getDoctorNameSnapshot()).isEqualTo("BS Tiến");
        assertThat(invoice.getItems()).hasSize(2);
        MedicineInvoiceItem first = invoice.getItems().get(0);
        assertThat(first.getPrescribedQuantity()).isEqualTo(10);
        assertThat(first.getQuantity()).isEqualTo(10);   // mặc định mua đúng đơn
        assertThat(first.getUnitPrice()).isEqualByComparingTo("15000");   // giá hiện hành của bảng giá
        assertThat(first.getVatRate()).isEqualByComparingTo("0.05");
        verify(inventoryRepository, never()).save(any());   // nháp không đụng kho
        verify(stockRepository, never()).deduct(any(UUID.class), anyInt());
    }

    @Test
    void createDraft_medicineWithoutEffectivePriceIsRefused_PHARM_1412() {
        // thuốc không có trong bảng giá thử
        Medicine unpriced = Medicine.builder().id(mid(900L)).category(TestData.category()).code("MED_900").name("Chưa có giá").dosageForm("Viên")
                .baseUnit("Viên").packagingSpec("Hộp").build();
        catalog.put(unpriced.getId(), unpriced);
        when(invoiceRepository.findByPrescriptionIdForUpdate(PRESCRIPTION)).thenReturn(Optional.empty());

        assertError(ErrorCode.MEDICINE_PRICE_NOT_FOUND, () -> service.createDraft(event(rx(900, 5))));
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    @Test
    void createDraft_redeliveredEventForPaidOrCancelledInvoiceDoesNotCreateAnotherInvoice_PHARM_1407() {
        for (PaymentStatus status : List.of(PaymentStatus.PAID, PaymentStatus.CANCELLED)) {
            MedicineInvoice existing = pendingInvoice(10, 2);
            existing.setPaymentStatus(status);
            when(invoiceRepository.findByPrescriptionIdForUpdate(PRESCRIPTION)).thenReturn(Optional.of(existing));

            service.createDraft(event(rx(1, 99)));   // chỉ ghi log, không đổi hóa đơn

            assertThat(existing.getItems()).hasSize(2);
            assertThat(existing.getItems().get(0).getQuantity()).isEqualTo(10);
        }
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    @Test
    void createDraft_reclosingAfterReopenRebuildsPendingDraftByMedicine() {
        // Bác sĩ mở lại hồ sơ (T14) rồi chốt lại: bỏ Drontal, đổi Clavamox 10 -> 12, thêm thuốc mới (#3)
        Medicine third = catalog.computeIfAbsent(mid(3L), k -> medicine(3L, "Vitamin", "5000", 50));
        MedicineInvoice existing = pendingInvoice(7, 2);   // khách đã sửa còn 7 viên và đã có giảm giá
        existing.setDiscountAmount(new BigDecimal("1000"));
        MedicineInvoiceItem amoxLine = existing.getItems().get(0);
        when(invoiceRepository.findByPrescriptionIdForUpdate(PRESCRIPTION)).thenReturn(Optional.of(existing));

        service.createDraft(event(rx(1, 12), rx(3, 4)));

        assertThat(existing.getItems()).extracting(i -> i.getMedicine().getId()).containsExactlyInAnyOrder(mid(1L), mid(3L));
        assertThat(existing.getItems()).contains(amoxLine);   // dòng cũ của Clavamox được SỬA (không xóa rồi thêm: UNIQUE(invoice, medicine))
        assertThat(amoxLine.getPrescribedQuantity()).isEqualTo(12);
        assertThat(amoxLine.getQuantity()).isEqualTo(12);
        assertThat(existing.findItemByMedicineId(third.getId())).isPresent();
        assertThat(existing.getDiscountAmount()).isEqualByComparingTo("0");
        assertThat(existing.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(invoiceRepository).saveAndFlush(existing);
    }

    // ================================================================== tra cứu

    @Test
    void statusOfPrescription_reportsInvoiceStatusOrNoneAtAll() {
        MedicineInvoice paid = pendingInvoice(10, 0);
        paid.setPaymentStatus(PaymentStatus.PAID);
        when(invoiceRepository.findByPrescriptionId(PRESCRIPTION)).thenReturn(Optional.of(paid));
        when(invoiceRepository.findByPrescriptionId(pid(4))).thenReturn(Optional.empty());

        MedicineInvoiceStatusResponse has = service.statusOfPrescription(PRESCRIPTION);
        assertThat(has.invoiceId()).isEqualTo(INVOICE);
        assertThat(has.status()).isEqualTo(PaymentStatus.PAID);
        MedicineInvoiceStatusResponse none = service.statusOfPrescription(pid(4));
        assertThat(none.invoiceId()).isNull();
        assertThat(none.status()).isNull();
    }

    @Test
    void getAndFindByVisit_returnAmountsComputedFromLines() {
        MedicineInvoice invoice = pendingInvoice(10, 0);
        when(invoiceRepository.findWithItemsById(INVOICE)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findWithItemsByVisitIdOrderByCreatedAtDesc(VISIT)).thenReturn(List.of(invoice));

        assertThat(service.getById(INVOICE).payableAmount()).isEqualByComparingTo("157500");   // 10 x 15.000 x 1,05
        assertThat(service.findByVisit(VISIT)).hasSize(1);
        assertThat(service.findByVisit(VISIT).get(0).items().get(0).prescribedQuantity()).isEqualTo(10);
        when(invoiceRepository.findWithItemsById(uid(404))).thenReturn(Optional.empty());
        assertError(ErrorCode.INVOICE_NOT_FOUND, () -> service.getById(uid(404)));
    }

    // ================================================================== sửa / hủy

    @Test
    void updateItems_changesQuantityKeepingTheSnapshotPrice_beyondPrescribedIsFine() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice invoice = pendingInvoice(10, 0);
        when(invoiceRepository.findByIdForUpdate(INVOICE)).thenReturn(Optional.of(invoice));

        assertThat(service.updateItems(INVOICE, buy(null, line(1, 8))).payableAmount()).isEqualByComparingTo("126000");
        assertThat(service.updateItems(INVOICE, buy(null, line(1, 25))).items().get(0).quantity()).isEqualTo(25);   // 25 > 10 đã kê: được
    }

    @Test
    void updateItems_zeroQuantityRemovesTheLine_butAtLeastOneMustRemain() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice invoice = pendingInvoice(10, 2);
        when(invoiceRepository.findByIdForUpdate(INVOICE)).thenReturn(Optional.of(invoice));

        MedicineInvoiceResponse response = service.updateItems(INVOICE, buy(null, line(2, 0)));
        assertThat(response.items()).hasSize(1);
        assertThat(response.payableAmount()).isEqualByComparingTo("157500");

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.updateItems(INVOICE, buy(null, line(1, 0))));   // bỏ nốt dòng cuối
    }

    @Test
    void updateItems_rechecksTheDiscountAgainstTheNewTotal() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice invoice = pendingInvoice(10, 0);
        invoice.setDiscountAmount(new BigDecimal("100000"));
        when(invoiceRepository.findByIdForUpdate(INVOICE)).thenReturn(Optional.of(invoice));

        // giảm còn 5 viên => tổng 78.750 < giảm giá 100.000 đang có
        assertError(ErrorCode.DISCOUNT_EXCEEDS_INVOICE_AMOUNT, () -> service.updateItems(INVOICE, buy(null, line(1, 5))));
        // đổi cả giảm giá cho khớp thì được
        assertThat(service.updateItems(INVOICE, buy("8750", line(1, 5))).payableAmount()).isEqualByComparingTo("70000");
    }

    @Test
    void updateItems_beyondStockUnknownLineAndDuplicatesAreRefused() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice invoice = pendingInvoice(10, 0);
        when(invoiceRepository.findByIdForUpdate(INVOICE)).thenReturn(Optional.of(invoice));

        assertError(ErrorCode.INSUFFICIENT_STOCK, () -> service.updateItems(INVOICE, buy(null, line(1, 201))));   // kho còn 200
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.updateItems(INVOICE, buy(null, line(2, 1))));   // Drontal không thuộc hóa đơn
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.updateItems(INVOICE, buy(null, line(1, 1), line(1, 2))));   // trùng dòng
        assertThat(service.updateItems(INVOICE, buy(null, line(1, 200))).items().get(0).quantity()).isEqualTo(200);   // đúng bằng tồn kho thì được
    }

    @Test
    void updateAndCancel_onlyWhilePending() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice paid = pendingInvoice(10, 0);
        paid.setPaymentStatus(PaymentStatus.PAID);
        MedicineInvoice cancelled = pendingInvoice(10, 0);
        cancelled.setPaymentStatus(PaymentStatus.CANCELLED);
        when(invoiceRepository.findByIdForUpdate(INVOICE)).thenReturn(Optional.of(paid));
        when(invoiceRepository.findByIdForUpdate(uid(501))).thenReturn(Optional.of(cancelled));

        assertError(ErrorCode.INVOICE_ALREADY_PAID, () -> service.updateItems(INVOICE, buy(null, line(1, 1))));
        assertError(ErrorCode.INVOICE_ALREADY_PAID, () -> service.cancel(INVOICE));
        assertError(ErrorCode.INVOICE_ALREADY_CANCELLED, () -> service.updateItems(uid(501), buy(null, line(1, 1))));
        assertError(ErrorCode.INVOICE_ALREADY_CANCELLED, () -> service.cancel(uid(501)));
    }

    @Test
    void cancel_pendingInvoiceBecomesCancelledAndStockIsUntouched() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice invoice = pendingInvoice(10, 0);
        when(invoiceRepository.findByIdForUpdate(INVOICE)).thenReturn(Optional.of(invoice));

        MedicineInvoiceResponse response = service.cancel(INVOICE);

        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(stockRepository, never()).deduct(any(UUID.class), anyInt());
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    void unknownInvoice() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(invoiceRepository.findByIdForUpdate(uid(404))).thenReturn(Optional.empty());

        assertError(ErrorCode.INVOICE_NOT_FOUND, () -> service.cancel(uid(404)));
        assertError(ErrorCode.INVOICE_NOT_FOUND, () -> service.confirmPayment(uid(404)));
    }

    // ================================================================== thu tiền + trừ kho

    @Test
    void confirmPayment_marksPaid_deductsStock_logsSignedExportSale_andWritesOutbox() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice invoice = pendingInvoice(10, 0);
        stockDb.put(mid(1L), 200);
        when(invoiceRepository.findByIdForUpdate(INVOICE)).thenReturn(Optional.of(invoice));

        MedicineInvoiceResponse response = service.confirmPayment(INVOICE);

        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(response.cashierId()).isEqualTo(RECEPTIONIST);
        assertThat(response.paidAt()).isEqualTo(CLOCK.instant());
        assertThat(stockDb.get(mid(1L))).isEqualTo(190);   // 200 -> 190

        ArgumentCaptor<InventoryTransaction> log = ArgumentCaptor.forClass(InventoryTransaction.class);
        verify(inventoryRepository).save(log.capture());
        assertThat(log.getValue().getTransactionType()).isEqualTo(InventoryTransactionType.EXPORT_SALE);
        assertThat(log.getValue().getQuantity()).isEqualTo(-10);   // quantity có dấu
        assertThat(log.getValue().getMedicineInvoiceId()).isEqualTo(INVOICE);
        assertThat(log.getValue().getCreatedBy()).isEqualTo(RECEPTIONIST);

        ArgumentCaptor<DomainEvent> events = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter).write(eq("MEDICINE_INVOICE"), eq(INVOICE), events.capture());
        MedicineInvoicePaidEvent paid = (MedicineInvoicePaidEvent) events.getValue();
        assertThat(paid.payableAmount()).isEqualByComparingTo("157500");
        assertThat(paid.visitId()).isEqualTo(VISIT);
        assertThat(paid.prescriptionId()).isEqualTo(PRESCRIPTION);
        verify(stockAlerts).publishIfLow(amox(), RECEPTIONIST);
    }

    @Test
    void confirmPayment_refreshesPriceToTheCurrentPriceBook_EX20() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice invoice = pendingInvoice(10, 0);
        invoice.getItems().get(0).setUnitPrice(new BigDecimal("10000"));   // nháp lập từ lâu với giá cũ
        stockDb.put(mid(1L), 200);
        when(invoiceRepository.findByIdForUpdate(INVOICE)).thenReturn(Optional.of(invoice));

        MedicineInvoiceResponse response = service.confirmPayment(INVOICE);

        assertThat(response.items().get(0).unitPrice()).isEqualByComparingTo("15000");   // bảng giá hiện hành
        assertThat(response.payableAmount()).isEqualByComparingTo("157500");
    }

    @Test
    void confirmPayment_refreshedPriceMakingTheDiscountTooLargeIsRefused() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice invoice = pendingInvoice(1, 0);
        invoice.setDiscountAmount(new BigDecimal("15750"));   // đúng bằng 1 viên giá hiện hành
        invoice.getItems().get(0).setUnitPrice(new BigDecimal("20000"));   // giá snapshot cũ cao hơn
        stockDb.put(mid(1L), 200);
        when(invoiceRepository.findByIdForUpdate(INVOICE)).thenReturn(Optional.of(invoice));

        assertThat(service.confirmPayment(INVOICE).payableAmount()).isEqualByComparingTo("0");
        invoice.setPaymentStatus(PaymentStatus.PENDING);
        invoice.setDiscountAmount(new BigDecimal("15750.01"));
        assertError(ErrorCode.DISCOUNT_EXCEEDS_INVOICE_AMOUNT, () -> service.confirmPayment(INVOICE));
    }

    @Test
    void confirmPayment_deductsInMedicineIdOrder_toAvoidDeadlocks() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        // dòng hóa đơn xếp ngược: Drontal (#2) trước Clavamox (#1)
        MedicineInvoice invoice = MedicineInvoice.builder().id(INVOICE).invoiceCode("MINV-2026-ABC234").prescriptionId(PRESCRIPTION).visitId(VISIT).build();
        invoice.addItem(MedicineInvoiceItem.builder().medicine(drontal()).prescribedQuantity(2).quantity(2)
                .unitPrice(new BigDecimal("45000")).vatRate(new BigDecimal("0.05")).build());
        invoice.addItem(MedicineInvoiceItem.builder().medicine(amox()).prescribedQuantity(10).quantity(10)
                .unitPrice(new BigDecimal("15000")).vatRate(new BigDecimal("0.05")).build());
        stockDb.put(mid(1L), 200);
        stockDb.put(mid(2L), 100);
        when(invoiceRepository.findByIdForUpdate(INVOICE)).thenReturn(Optional.of(invoice));

        service.confirmPayment(INVOICE);

        InOrder order = inOrder(stockRepository);
        order.verify(stockRepository).deduct(mid(1L), 10);
        order.verify(stockRepository).deduct(mid(2L), 2);
    }

    @Test
    void confirmPayment_insufficientStockOnAnyLineAbortsTheWholeSale_invoiceStaysPending_EX15() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice invoice = pendingInvoice(10, 2);
        stockDb.put(mid(1L), 200);
        stockDb.put(mid(2L), 1);   // Drontal chỉ còn 1, cần 2
        when(invoiceRepository.findByIdForUpdate(INVOICE)).thenReturn(Optional.of(invoice));

        assertError(ErrorCode.INSUFFICIENT_STOCK, () -> service.confirmPayment(INVOICE));

        verify(invoiceRepository, never()).saveAndFlush(any());
        verify(outboxWriter, never()).write(any(), any(), any(DomainEvent.class));   // không có thông báo giả
        verify(stockAlerts, never()).publishIfLow(any(), any());
    }

    @Test
    void confirmPayment_twiceIsRefusedWithoutTouchingStock_EX16() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice invoice = pendingInvoice(10, 0);
        invoice.setPaymentStatus(PaymentStatus.PAID);   // lần bấm thứ hai thấy PAID
        when(invoiceRepository.findByIdForUpdate(INVOICE)).thenReturn(Optional.of(invoice));

        assertError(ErrorCode.INVOICE_ALREADY_PAID, () -> service.confirmPayment(INVOICE));
        verify(stockRepository, never()).deduct(any(UUID.class), anyInt());
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    void confirmPayment_cancelledInvoiceIsRefused() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        MedicineInvoice invoice = pendingInvoice(10, 0);
        invoice.setPaymentStatus(PaymentStatus.CANCELLED);
        when(invoiceRepository.findByIdForUpdate(INVOICE)).thenReturn(Optional.of(invoice));

        assertError(ErrorCode.INVOICE_ALREADY_CANCELLED, () -> service.confirmPayment(INVOICE));
    }
}
