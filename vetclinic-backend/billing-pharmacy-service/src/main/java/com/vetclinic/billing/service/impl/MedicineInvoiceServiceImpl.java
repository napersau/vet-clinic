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
import com.vetclinic.billing.entity.MedicinePrice;
import com.vetclinic.billing.repository.InventoryTransactionRepository;
import com.vetclinic.billing.repository.MedicineInvoiceRepository;
import com.vetclinic.billing.repository.MedicineRepository;
import com.vetclinic.billing.repository.MedicineStockRepository;
import com.vetclinic.billing.service.BillingAssembler;
import com.vetclinic.billing.service.MedicineInvoiceAmounts;
import com.vetclinic.billing.service.MedicineInvoiceService;
import com.vetclinic.billing.service.MedicinePriceBook;
import com.vetclinic.billing.service.StockAlertPublisher;
import com.vetclinic.common.event.MedicineInvoicePaidEvent;
import com.vetclinic.common.event.PrescriptionCreatedEvent;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.SecurityUtils;
import com.vetclinic.common.util.Codes;
import com.vetclinic.common.util.UuidV7;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * KHÔNG @Transactional ở mức class: các thao tác ghi chạy trong TransactionTemplate. Thao tác ghi khóa dòng hóa đơn (FOR UPDATE) đầu giao dịch nên
 * hai thu ngân bấm cùng lúc thì người sau chờ rồi thấy PAID (EX-16); @Version là lớp bảo vệ thứ hai.
 */
@Slf4j
@Service
public class MedicineInvoiceServiceImpl implements MedicineInvoiceService {

    /** aggregate_type của các dòng outbox gắn với một hóa đơn thuốc (aggregate_id = id hóa đơn). */
    private static final String AGGREGATE_INVOICE = "MEDICINE_INVOICE";

    private final MedicineInvoiceRepository invoiceRepository;
    private final MedicineRepository medicineRepository;
    private final MedicineStockRepository stockRepository;
    private final MedicinePriceBook priceBook;
    private final InventoryTransactionRepository inventoryRepository;
    private final BillingAssembler assembler;
    private final StockAlertPublisher stockAlerts;
    private final OutboxWriter outboxWriter;
    private final EntityManager entityManager;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public MedicineInvoiceServiceImpl(MedicineInvoiceRepository invoiceRepository, MedicineRepository medicineRepository,
                                      MedicineStockRepository stockRepository, MedicinePriceBook priceBook,
                                      InventoryTransactionRepository inventoryRepository, BillingAssembler assembler,
                                      StockAlertPublisher stockAlerts, OutboxWriter outboxWriter, EntityManager entityManager,
                                      Clock clock, PlatformTransactionManager transactionManager) {
        this.invoiceRepository = invoiceRepository;
        this.medicineRepository = medicineRepository;
        this.stockRepository = stockRepository;
        this.priceBook = priceBook;
        this.inventoryRepository = inventoryRepository;
        this.assembler = assembler;
        this.stockAlerts = stockAlerts;
        this.outboxWriter = outboxWriter;
        this.entityManager = entityManager;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    // ================================================================== nháp từ sự kiện chốt ca

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createDraft(PrescriptionCreatedEvent event) {
        // Khóa theo đơn thuốc: không chen vào lúc Thu ngân đang sửa / thu hóa đơn của chính đơn này
        Optional<MedicineInvoice> existing = invoiceRepository.findByPrescriptionIdForUpdate(event.prescriptionId());
        if (existing.isPresent()) {
            MedicineInvoice invoice = existing.get();
            if (invoice.getPaymentStatus() != PaymentStatus.PENDING) {
                log.warn("{}: đơn thuốc {} đã có hóa đơn {} ở trạng thái {}, bỏ qua sự kiện {}", ErrorCode.PRESCRIPTION_ALREADY_INVOICED.getCode(),
                        event.prescriptionId(), invoice.getInvoiceCode(), invoice.getPaymentStatus(), event.eventId());
                return;
            }
            rebuildDraft(invoice, event);   // Bác sĩ mở lại hồ sơ (T14) rồi chốt lại: dựng lại nháp theo đơn mới
            invoiceRepository.saveAndFlush(invoice);
            return;
        }
        MedicineInvoice invoice = MedicineInvoice.builder()
                .invoiceCode(String.format("MINV-%d-%s", LocalDate.now(clock).getYear(), Codes.randomSuffix(6)))
                .prescriptionId(event.prescriptionId())
                .visitId(event.visitId())
                .customerNameSnapshot(event.customerName())
                .customerPhoneSnapshot(event.customerPhone())
                .petNameSnapshot(event.petName())
                .doctorNameSnapshot(event.doctorName())
                .build();
        for (PrescriptionCreatedEvent.Item item : event.items()) {
            invoice.addItem(draftLine(item));
        }
        invoiceRepository.saveAndFlush(invoice);   // UNIQUE(prescription_id): hai sự kiện trùng đồng thời thì một bên vi phạm và được thử lại / bỏ qua
    }

    /**
     * Dựng lại nháp theo đơn mới theo THUỐC (sửa dòng cũ, bỏ dòng không còn, thêm dòng mới): xóa rồi thêm lại cùng thuốc sẽ vi phạm
     * UNIQUE(invoice, medicine) vì INSERT chạy trước DELETE. Số lượng mua trở về số kê, giá làm mới, giảm giá về 0 (Thu ngân sửa lại sau).
     */
    private void rebuildDraft(MedicineInvoice invoice, PrescriptionCreatedEvent event) {
        invoice.setCustomerNameSnapshot(event.customerName());
        invoice.setCustomerPhoneSnapshot(event.customerPhone());
        invoice.setPetNameSnapshot(event.petName());
        invoice.setDoctorNameSnapshot(event.doctorName());
        invoice.setDiscountAmount(BigDecimal.ZERO);
        Set<UUID> keep = new HashSet<>();
        for (PrescriptionCreatedEvent.Item item : event.items()) {
            keep.add(item.medicineId());
            Optional<MedicineInvoiceItem> line = invoice.findItemByMedicineId(item.medicineId());
            if (line.isPresent()) {
                MedicinePrice price = priceBook.current(item.medicineId());
                line.get().setPrescribedQuantity(item.quantity());
                line.get().setQuantity(item.quantity());
                line.get().setUnitPrice(price.getSalePrice());
                line.get().setVatRate(price.getVatRate());
            } else {
                invoice.addItem(draftLine(item));
            }
        }
        invoice.getItems().removeIf(i -> !keep.contains(i.getMedicine().getId()));
    }

    private MedicineInvoiceItem draftLine(PrescriptionCreatedEvent.Item item) {
        MedicinePrice price = priceBook.current(item.medicineId());   // PHARM_1412 nếu thuốc chưa có giá hiện hành
        return MedicineInvoiceItem.builder()
                .medicine(medicineRepository.getReferenceById(item.medicineId()))
                .prescribedQuantity(item.quantity())
                .quantity(item.quantity())   // mặc định mua đúng đơn
                .unitPrice(price.getSalePrice())
                .vatRate(price.getVatRate())
                .build();
    }

    // ================================================================== tra cứu

    @Override
    public List<MedicineInvoiceResponse> findByVisit(UUID visitId) {
        return readTx.execute(s -> invoiceRepository.findWithItemsByVisitIdOrderByCreatedAtDesc(visitId).stream().map(assembler::toResponse).toList());
    }

    @Override
    public MedicineInvoiceResponse getById(UUID id) {
        return readTx.execute(s -> assembler.toResponse(invoiceRepository.findWithItemsById(id)
                .orElseThrow(() -> new AppException(ErrorCode.INVOICE_NOT_FOUND))));
    }

    @Override
    @Transactional(readOnly = true)
    public MedicineInvoiceStatusResponse statusOfPrescription(UUID prescriptionId) {
        return invoiceRepository.findByPrescriptionId(prescriptionId)
                .map(i -> new MedicineInvoiceStatusResponse(prescriptionId, i.getId(), i.getPaymentStatus()))
                .orElse(new MedicineInvoiceStatusResponse(prescriptionId, null, null));
    }

    // ================================================================== sửa / hủy

    @Override
    public MedicineInvoiceResponse updateItems(UUID id, MedicineInvoiceRequest request) {
        return tx.execute(s -> doUpdateItems(id, request));
    }

    private MedicineInvoiceResponse doUpdateItems(UUID id, MedicineInvoiceRequest request) {
        MedicineInvoice invoice = invoiceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.INVOICE_NOT_FOUND));
        assertPending(invoice);

        Set<UUID> seen = new HashSet<>();
        for (MedicineInvoiceRequest.Line line : request.items()) {
            MedicineInvoiceItem item = invoice.findItemByMedicineId(line.medicineId())
                    .orElseThrow(() -> new AppException(ErrorCode.INVALID_INPUT_DATA,
                            "Thuốc " + line.medicineId() + " không có trong hóa đơn này (chỉ sửa được thuốc thuộc đơn)"));
            if (!seen.add(line.medicineId())) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                        "Thuốc [" + item.getMedicine().getName() + "] xuất hiện nhiều lần, hãy gộp thành một dòng");
            }
            if (line.quantity() == 0) {
                invoice.getItems().remove(item);   // quantity = 0: bỏ dòng khỏi hóa đơn
            } else {
                assertWithinStock(item.getMedicine(), line.quantity());   // BR-32: khác số kê được, nhưng không vượt tồn kho
                item.setQuantity(line.quantity());   // giữ nguyên snapshot giá tới lúc thu tiền
            }
        }
        if (invoice.getItems().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Hóa đơn phải còn ít nhất một dòng thuốc; nếu khách mua ngoài hãy hủy hóa đơn");
        }
        BigDecimal discount = request.discountAmount() == null ? invoice.getDiscountAmount()
                : MedicineInvoiceAmounts.money(request.discountAmount());
        assertDiscountWithinInvoice(discount, invoice.getItems());   // kiểm tra lại cả khi chỉ đổi số lượng (EX-14)
        invoice.setDiscountAmount(discount);
        return assembler.toResponse(invoiceRepository.saveAndFlush(invoice));
    }

    @Override
    public MedicineInvoiceResponse cancel(UUID id) {
        return tx.execute(s -> {
            MedicineInvoice invoice = invoiceRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new AppException(ErrorCode.INVOICE_NOT_FOUND));
            assertPending(invoice);
            invoice.setPaymentStatus(PaymentStatus.CANCELLED);   // khách mua ngoài; kho không đổi (chưa trừ); hủy là kết thúc
            return assembler.toResponse(invoiceRepository.saveAndFlush(invoice));
        });
    }

    // ================================================================== thu tiền + trừ kho

    @Override
    public MedicineInvoiceResponse confirmPayment(UUID id) {
        UUID cashierId = SecurityUtils.getCurrentUserId();
        return tx.execute(s -> doConfirmPayment(id, cashierId));
    }

    private MedicineInvoiceResponse doConfirmPayment(UUID id, UUID cashierId) {
        // Khóa dòng hóa đơn: hai thu ngân bấm cùng lúc thì người sau chờ, rồi thấy PAID -> BILL_1403 (EX-16); @Version là lớp bảo vệ thứ hai
        MedicineInvoice invoice = invoiceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.INVOICE_NOT_FOUND));
        assertPending(invoice);

        // EX-20: giá được làm mới theo bảng giá hiện hành ngay lúc thu (nháp có thể đã tạo từ lâu); kiểm tra lại giảm giá với giá mới
        for (MedicineInvoiceItem line : invoice.getItems()) {
            MedicinePrice price = priceBook.current(line.getMedicine().getId());
            line.setUnitPrice(price.getSalePrice());
            line.setVatRate(price.getVatRate());
        }
        assertDiscountWithinInvoice(invoice.getDiscountAmount(), invoice.getItems());

        // Sắp theo id thuốc để hai giao dịch bán chồng nhau luôn khóa theo cùng thứ tự, không deadlock
        List<MedicineInvoiceItem> lines = invoice.getItems().stream()
                .sorted(Comparator.comparing((MedicineInvoiceItem i) -> i.getMedicine().getId())).toList();
        List<Medicine> touched = new ArrayList<>();
        for (MedicineInvoiceItem line : lines) {
            Medicine medicine = line.getMedicine();
            if (stockRepository.deduct(medicine.getId(), line.getQuantity()) == 0) {
                // UPDATE medicine_stocks ... WHERE quantity_on_hand >= :qty ảnh hưởng 0 dòng = thiếu hàng: rollback TOÀN BỘ (kể cả các dòng đã trừ)
                entityManager.refresh(medicine);
                throw new AppException(ErrorCode.INSUFFICIENT_STOCK, "Không thể bán: thuốc [" + medicine.getName()
                        + "] trong kho không đủ số lượng (còn " + medicine.getStockQuantity() + ", cần " + line.getQuantity()
                        + "). Hãy sửa số lượng trong hóa đơn hoặc báo kho nhập thêm hàng.");
            }
            entityManager.refresh(medicine);   // tồn kho thật sau khi trừ (không tin giá trị cũ trong entity)
            inventoryRepository.save(InventoryTransaction.builder()
                    .medicineId(medicine.getId())
                    .transactionType(InventoryTransactionType.EXPORT_SALE)
                    .quantity(-line.getQuantity())   // quantity có dấu: xuất là số âm
                    .medicineInvoiceId(invoice.getId())
                    .createdBy(cashierId)
                    .build());
            touched.add(medicine);
        }

        invoice.setPaymentStatus(PaymentStatus.PAID);
        invoice.setCashierId(cashierId);
        invoice.setPaidAt(clock.instant());
        MedicineInvoice saved = invoiceRepository.saveAndFlush(invoice);   // kiểm tra @Version ngay trong giao dịch

        // Transactional Outbox: ghi cùng giao dịch trừ kho + thu tiền; OutboxRelay gửi sang RabbitMQ sau khi commit
        outboxWriter.write(AGGREGATE_INVOICE, saved.getId(), new MedicineInvoicePaidEvent(UuidV7.generate(clock), clock.instant(), cashierId,
                saved.getId(), saved.getInvoiceCode(), saved.getPrescriptionId(), saved.getVisitId(),
                MedicineInvoiceAmounts.payable(saved.getItems(), saved.getDiscountAmount()), saved.getDiscountAmount(), saved.getPaidAt(),
                saved.getItems().stream().map(i -> new MedicineInvoicePaidEvent.Line(i.getMedicine().getId(), i.getMedicine().getCode(),
                        i.getMedicine().getName(), i.getMedicine().getCategory().getCode(), i.getMedicine().getCategory().getName(),
                        i.getQuantity(), i.getUnitPrice(), i.getVatRate())).toList()));
        touched.forEach(medicine -> stockAlerts.publishIfLow(medicine, cashierId));
        return assembler.toResponse(saved);
    }

    // ================================================================== helper

    private static void assertPending(MedicineInvoice invoice) {
        if (invoice.getPaymentStatus() == PaymentStatus.PAID) {
            throw new AppException(ErrorCode.INVOICE_ALREADY_PAID);
        }
        if (invoice.getPaymentStatus() == PaymentStatus.CANCELLED) {
            throw new AppException(ErrorCode.INVOICE_ALREADY_CANCELLED);
        }
    }

    /**
     * Số lượng bác sĩ kê chỉ để tham khảo (khách có thể mua nhiều hơn / ít hơn / bằng), nhưng không được bán quá tồn kho hiện tại.
     * Kiểm tra sớm ở bước sửa hóa đơn; lúc thu tiền vẫn kiểm tra lại bằng UPDATE nguyên tử (kho có thể đổi giữa hai bước, EX-15).
     * Mức tồn tối thiểu ({@code min_stock_alert}) chỉ là ngưỡng cảnh báo cho Admin, không chặn bán.
     */
    private static void assertWithinStock(Medicine medicine, int quantity) {
        if (quantity > medicine.getStockQuantity()) {
            throw new AppException(ErrorCode.INSUFFICIENT_STOCK, "Không thể bán thuốc [" + medicine.getName() + "] với số lượng "
                    + quantity + ": trong kho chỉ còn " + medicine.getStockQuantity() + " " + medicine.getUnit());
        }
    }

    /** Giảm giá không được vượt tổng tiền thuốc của chính hóa đơn này (BILL_1402, EX-14). */
    private static void assertDiscountWithinInvoice(BigDecimal discount, List<MedicineInvoiceItem> items) {
        if (discount.compareTo(MedicineInvoiceAmounts.gross(items)) > 0) {
            throw new AppException(ErrorCode.DISCOUNT_EXCEEDS_INVOICE_AMOUNT);
        }
    }
}
