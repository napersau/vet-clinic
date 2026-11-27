package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.constant.PaymentStatus;
import com.vetclinic.clinical.entity.ServiceCategory;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.InvoiceStatementResponse;
import com.vetclinic.clinical.dto.response.ServiceInvoiceResponse;
import com.vetclinic.clinical.dto.response.TicketInfo;
import com.vetclinic.clinical.queue.QueueCoordinator;
import com.vetclinic.clinical.queue.TicketView;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.ServiceInvoice;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.ServiceInvoiceRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.InvoiceAmounts;
import com.vetclinic.clinical.service.ServiceInvoiceService;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.common.util.UuidV7;
import com.vetclinic.common.event.ServiceInvoicePaidEvent;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ServiceInvoiceServiceImpl implements ServiceInvoiceService {

    private final PetVisitRepository visitRepository;
    private final VisitServiceDetailRepository detailRepository;
    private final ServiceInvoiceRepository invoiceRepository;
    private final VisitSupport support;
    private final QueueCoordinator queue;
    private final VisitAssembler assembler;
    private final OutboxWriter outboxWriter;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ServiceInvoiceServiceImpl(PetVisitRepository visitRepository, VisitServiceDetailRepository detailRepository,
                                     ServiceInvoiceRepository invoiceRepository,
                                     VisitSupport support, QueueCoordinator queue, VisitAssembler assembler,
                                     OutboxWriter outboxWriter, Clock clock,
                                     PlatformTransactionManager transactionManager) {
        this.visitRepository = visitRepository;
        this.detailRepository = detailRepository;
        this.invoiceRepository = invoiceRepository;
        this.support = support;
        this.queue = queue;
        this.assembler = assembler;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ================================================================== bảng kê (UC-34)

    @Override
    @Transactional(readOnly = true)
    public InvoiceStatementResponse statement(UUID visitId) {
        PetVisit visit = visitRepository.findById(visitId).orElseThrow(() -> new AppException(ErrorCode.VISIT_NOT_FOUND));
        return buildStatement(visit);
    }

    private InvoiceStatementResponse buildStatement(PetVisit visit) {
        List<VisitServiceDetail> details = detailRepository.findByVisitIdOrderByIdAsc(visit.getId());
        Map<UUID, ServiceInvoice> invoices = support.invoicesOf(details.stream().map(VisitServiceDetail::getId).toList());
        List<ServiceInvoiceResponse> items = details.stream()
                .filter(d -> invoices.containsKey(d.getId()))
                .map(d -> toResponse(invoices.get(d.getId()), d))
                .toList();
        List<TicketInfo> tickets = ticketsOf(visit.getId());
        return new InvoiceStatementResponse(visit.getId(), visit.getVisitCode(), visit.getStatus(), items,
                sum(items, PaymentStatus.PENDING), sum(items, PaymentStatus.PAID), sum(items, PaymentStatus.REFUNDED), tickets);
    }

    /**
     * Phiếu đang mở của ca, đọc từ Redis. Phiếu phát SAU khi thu tiền (qua sự kiện, thường dưới 1 giây) nên ngay sau khi thu có thể chưa thấy; màn hình
     * hiển thị "Đang cấp số thứ tự…" rồi tải lại. Redis lỗi thì trả rỗng chứ không làm hỏng bảng kê hóa đơn (hóa đơn nằm ở MySQL).
     */
    private List<TicketInfo> ticketsOf(UUID visitId) {
        try {
            return queue.openTicketsOf(visitId).stream().map(TicketView::info).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static BigDecimal sum(List<ServiceInvoiceResponse> items, PaymentStatus status) {
        return items.stream().filter(i -> i.paymentStatus() == status).map(ServiceInvoiceResponse::payableAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private static ServiceInvoiceResponse toResponse(ServiceInvoice i, VisitServiceDetail d) {
        return new ServiceInvoiceResponse(i.getId(), i.getInvoiceCode(), d.getId(), d.getService().getId(), d.getService().getName(),
                d.getService().getCategory().getCode(), d.getQuantity(), i.getUnitPrice(), i.getVatRate(), i.getDiscountAmount(),
                InvoiceAmounts.beforeVat(d.getQuantity(), i.getUnitPrice()),
                InvoiceAmounts.vat(d.getQuantity(), i.getUnitPrice(), i.getVatRate()),
                InvoiceAmounts.payable(d.getQuantity(), i.getUnitPrice(), i.getVatRate(), i.getDiscountAmount()),
                i.getPaymentStatus(), i.getCashierId(), i.getPaidAt(), d.getService().isRequiresPrepayment());
    }

    // ================================================================== giảm giá (EX-14)

    @Override
    @Transactional
    public ServiceInvoiceResponse discount(UUID invoiceId, BigDecimal discountAmount) {
        ServiceInvoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(() -> new AppException(ErrorCode.INVOICE_NOT_FOUND));
        VisitServiceDetail detail = detailRepository.findById(invoice.getVisitServiceDetailId())
                .orElseThrow(() -> new AppException(ErrorCode.INVOICE_NOT_FOUND));
        requirePending(invoice);
        PetVisit visit = visitRepository.findById(detail.getVisitId()).orElseThrow(() -> new AppException(ErrorCode.VISIT_NOT_FOUND));
        // Dịch vụ thu trước giảm được ngay trước khi thu; dịch vụ làm tại phòng chỉ thu (và giảm) khi đã chốt ca
        if (!detail.getService().isRequiresPrepayment() && !isClosed(visit)) {
            throw new AppException(ErrorCode.VISIT_NOT_READY_FOR_PAYMENT);
        }
        BigDecimal discount = discountAmount.setScale(2, RoundingMode.HALF_UP);
        // Trần là tiền của CHÍNH hóa đơn này (không phải tổng cả ca)
        if (discount.compareTo(InvoiceAmounts.grossWithVat(detail.getQuantity(), invoice.getUnitPrice(), invoice.getVatRate())) > 0) {
            throw new AppException(ErrorCode.DISCOUNT_EXCEEDS_INVOICE_AMOUNT);
        }
        invoice.setDiscountAmount(discount);
        invoiceRepository.saveAndFlush(invoice);   // @Version: hai người cùng sửa một hóa đơn thì người đến sau nhận SYS_1005
        return toResponse(invoice, detail);
    }

    private static void requirePending(ServiceInvoice invoice) {
        if (invoice.getPaymentStatus() == PaymentStatus.PAID || invoice.getPaymentStatus() == PaymentStatus.REFUNDED) {
            throw new AppException(ErrorCode.INVOICE_ALREADY_PAID);
        }
        if (invoice.getPaymentStatus() == PaymentStatus.CANCELLED) {
            throw new AppException(ErrorCode.INVOICE_ALREADY_CANCELLED);
        }
    }

    private static boolean isClosed(PetVisit visit) {
        return visit.getStatus() == VisitStatus.WAITING_PAYMENT || visit.getStatus() == VisitStatus.COMPLETED;
    }

    // ================================================================== thu tiền + phát phiếu + hoàn tất ca (T10) trong MỘT giao dịch

    /**
     * Giao dịch chỉ ghi hóa đơn PAID + outbox {@code ServiceInvoicePaidEvent} (và hoàn tất ca nếu hết khoản chờ). KHÔNG phát phiếu ở đây: số phiếu do Redis
     * cấp bằng {@code INCR} nên hai quầy thu cùng lúc không còn tranh số (không còn vòng thử lại); {@code QueueIssuerListener} phát phiếu khi nhận sự kiện.
     */
    @Override
    public InvoiceStatementResponse confirmPayment(UUID visitId, List<UUID> invoiceIds) {
        UUID cashierId = SecurityUtils.getCurrentUserId();
        return tx.execute(s -> doConfirmPayment(visitId, invoiceIds, cashierId));
    }

    private InvoiceStatementResponse doConfirmPayment(UUID visitId, List<UUID> invoiceIds, UUID cashierId) {
        PetVisit visit = support.lock(visitId);   // khóa dòng ca: hai thu ngân cùng bấm thì người đến sau thấy hóa đơn đã PAID (BILL_1403)
        if (visit.getStatus() == VisitStatus.CANCELLED) {
            throw new AppException(ErrorCode.VISIT_NOT_READY_FOR_PAYMENT, "Đợt khám đã hủy, không còn khoản nào để thu");
        }
        List<VisitServiceDetail> details = detailRepository.findByVisitIdOrderByIdAsc(visit.getId());
        Map<UUID, VisitServiceDetail> detailById = details.stream().collect(Collectors.toMap(VisitServiceDetail::getId, d -> d));
        Map<UUID, ServiceInvoice> byDetail = support.invoicesOf(detailById.keySet());
        Map<UUID, ServiceInvoice> byInvoiceId = new HashMap<>();
        byDetail.values().forEach(i -> byInvoiceId.put(i.getId(), i));

        Set<UUID> wanted = new LinkedHashSet<>(invoiceIds);
        if (wanted.isEmpty() && (visit.getStatus() != VisitStatus.WAITING_PAYMENT
                || byInvoiceId.values().stream().anyMatch(i -> i.getPaymentStatus() == PaymentStatus.PENDING))) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Phải chọn ít nhất một hóa đơn để thu");
        }
        for (UUID id : wanted) {
            if (!byInvoiceId.containsKey(id)) {
                throw new AppException(ErrorCode.INVOICE_NOT_FOUND, "Hóa đơn " + id + " không thuộc đợt khám này");   // BILL_1401
            }
        }
        // Kiểm tra TẤT CẢ trước khi ghi: một hóa đơn không hợp lệ thì không thu hóa đơn nào
        boolean closed = isClosed(visit);
        for (UUID id : wanted) {
            ServiceInvoice invoice = byInvoiceId.get(id);
            requirePending(invoice);
            VisitServiceDetail detail = detailById.get(invoice.getVisitServiceDetailId());
            // BR-35: chính sách thu trước đọc từ danh mục; dịch vụ làm tại phòng chỉ thu khi Bác sĩ đã chốt chuyên môn
            if (!detail.getService().isRequiresPrepayment() && !closed) {
                throw new AppException(ErrorCode.VISIT_NOT_READY_FOR_PAYMENT);
            }
        }
        var now = clock.instant();
        List<ServiceInvoicePaidEvent.Line> paidLines = new ArrayList<>();
        for (UUID id : wanted) {
            ServiceInvoice invoice = byInvoiceId.get(id);
            invoice.setPaymentStatus(PaymentStatus.PAID);
            invoice.setCashierId(cashierId);
            invoice.setPaidAt(now);
            invoiceRepository.saveAndFlush(invoice);   // @Version chống thu trùng
            VisitServiceDetail detail = detailById.get(invoice.getVisitServiceDetailId());
            paidLines.add(lineOf(invoice, detail));
        }
        if (!paidLines.isEmpty()) {
            // Q1 (v4.0): thu tiền xong thì sự kiện đi qua outbox CÙNG giao dịch; QueueIssuerListener phát phiếu: phí khám -> DOCTOR; XN / CĐHA -> phiếu phòng
            outboxWriter.write(VisitSupport.OUTBOX_AGGREGATE, visit.getId(), new ServiceInvoicePaidEvent(UuidV7.generate(clock), now, cashierId,
                    visit.getId(), visit.getVisitCode(), visit.getPet().getId(), visit.getPet().getName(), visit.getPet().getOwnerNameSnapshot(),
                    visit.getDoctorId(), visit.getDoctorNameSnapshot(), visit.isEmergency(), cashierId, now, paidLines));
        }

        boolean anyPending = byInvoiceId.values().stream().anyMatch(i -> i.getPaymentStatus() == PaymentStatus.PENDING);
        if (!anyPending && visit.getStatus() == VisitStatus.WAITING_PAYMENT) {
            // T10: mọi hóa đơn PAID / CANCELLED / REFUNDED -> hoàn tất ca, người đổi trạng thái = thu ngân; ca COMPLETED do khách bỏ về thì giữ nguyên
            support.transition(visit, VisitStatus.COMPLETED, cashierId, "Đã thu đủ hóa đơn dịch vụ, hoàn tất đợt khám");
            outboxWriter.write(VisitSupport.OUTBOX_AGGREGATE, visit.getId(), new VisitCompletedEvent(UuidV7.generate(clock), clock.instant(), cashierId, visit.getId(),
                    visit.getVisitCode(), visit.getPet().getId(), false, visit.getDoctorId(), visit.getDoctorNameSnapshot()));
        }
        return buildStatement(visit);
    }

    private static ServiceInvoicePaidEvent.Line lineOf(ServiceInvoice invoice, VisitServiceDetail detail) {
        ClinicService service = detail.getService();
        var queueType = service.getCategory().queueTypeOrNull();
        return new ServiceInvoicePaidEvent.Line(invoice.getId(), invoice.getInvoiceCode(), detail.getId(), service.getId(), service.getCode(),
                service.getName(), service.getCategory().getCode(), service.getCategory().getName(), queueType == null ? null : queueType.name(), detail.getQuantity(),
                invoice.getUnitPrice(), invoice.getVatRate(), invoice.getDiscountAmount(),
                InvoiceAmounts.payable(detail.getQuantity(), invoice.getUnitPrice(), invoice.getVatRate(), invoice.getDiscountAmount()));
    }

    // ================================================================== màn hình Thu ngân (3 mục)

    @Override
    @Transactional(readOnly = true)
    public List<VisitResponse> cashierPending(CashierSection section) {
        List<PetVisit> visits = switch (section) {
            case EXAM_FEE -> visitRepository.findByStatusOrderByIdAsc(VisitStatus.WAITING);
            case PARACLINIC -> visitRepository.findByStatusOrderByIdAsc(VisitStatus.WAITING_RESULTS);
            case FINAL -> visitRepository.findByStatusOrderByIdAsc(VisitStatus.WAITING_PAYMENT);
        };
        if (section == CashierSection.FINAL || visits.isEmpty()) {
            return assembler.summaries(visits);
        }
        List<UUID> ids = visits.stream().map(PetVisit::getId).toList();
        // Ca cấp cứu khám trước, thu sau: không nằm ở mục "chờ nộp phí khám" (đã có phiếu ngay khi tiếp đón)
        Set<UUID> emergency = visits.stream().filter(PetVisit::isEmergency).map(PetVisit::getId).collect(Collectors.toSet());
        List<VisitServiceDetail> details = detailRepository.findByVisitIdInOrderByIdAsc(ids);
        Map<UUID, ServiceInvoice> invoices = support.invoicesOf(details.stream().map(VisitServiceDetail::getId).toList());

        Set<UUID> chosen = details.stream()
                .filter(d -> d.getStatus() == ServiceDetailStatus.PENDING && invoices.containsKey(d.getId())
                        && invoices.get(d.getId()).getPaymentStatus() == PaymentStatus.PENDING)
                .filter(d -> section == CashierSection.EXAM_FEE
                        ? d.getService().getCategory().is(ServiceCategory.EXAMINATION) && !emergency.contains(d.getVisitId())
                        : d.getService().getCategory().isParaclinical())
                .map(VisitServiceDetail::getVisitId)
                .collect(Collectors.toSet());
        return assembler.summaries(visits.stream().filter(v -> chosen.contains(v.getId())).toList());
    }

    // ================================================================== công nợ

    @Override
    @Transactional(readOnly = true)
    public List<VisitResponse> visitsWithDebt() {
        return assembler.summaries(visitRepository.findCompletedWithDebt());
    }
}
