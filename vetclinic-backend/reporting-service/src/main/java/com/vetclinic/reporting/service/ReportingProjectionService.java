package com.vetclinic.reporting.service;

import com.vetclinic.common.event.MedicineInvoicePaidEvent;
import com.vetclinic.common.event.PetUpdatedEvent;
import com.vetclinic.common.event.ServiceInvoicePaidEvent;
import com.vetclinic.common.event.ServiceInvoiceRefundedEvent;
import com.vetclinic.common.event.UserProfileUpdatedEvent;
import com.vetclinic.common.event.VisitCancelledEvent;
import com.vetclinic.common.event.VisitCheckedInEvent;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.reporting.config.ReportingProperties;
import com.vetclinic.reporting.constant.RevenueEntryType;
import com.vetclinic.reporting.constant.RevenueType;
import com.vetclinic.reporting.entity.RevenueFact;
import com.vetclinic.reporting.entity.VisitFact;
import com.vetclinic.reporting.repository.DailyRevenueSummaryRepository;
import com.vetclinic.reporting.repository.DoctorDailyPerformanceRepository;
import com.vetclinic.reporting.repository.RevenueFactRepository;
import com.vetclinic.reporting.repository.VisitFactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dựng mô hình đọc báo cáo (CQRS) từ sự kiện. Mọi phương thức chạy trong giao dịch của {@code IdempotentConsumer} (cùng giao dịch với dòng
 * {@code processed_events}), nên: nhận trùng sự kiện thì không cộng hai lần; lỗi giữa chừng thì rollback cả dòng fact lẫn bảng tổng hợp.
 * <p>
 * Quy ước: {@code business_date} theo giờ phòng khám; hoàn tiền là dòng REFUND mang số ÂM (sao chép dòng SALE gốc rồi đổi dấu) nên mọi tổng
 * chỉ là SUM(net_amount); bảng tổng hợp cộng dồn bằng một câu {@code INSERT ... ON DUPLICATE KEY UPDATE}. Sự kiện đến sớm hơn dữ liệu nó cần
 * (vd. hoàn tiền trước khi thấy dòng thu) ném {@link IllegalStateException} để RabbitMQ thử lại 3 lần rồi mới vào DLQ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportingProjectionService {

    /** Giá trị thay thế cho cột NOT NULL khi tin nhắn phát trước bước 7.1 (chưa mang thông tin đó). */
    static final String UNKNOWN = "(không rõ)";
    static final UUID NIL_UUID = new UUID(0L, 0L);

    private final VisitFactRepository visitFacts;
    private final RevenueFactRepository revenueFacts;
    private final DailyRevenueSummaryRepository summaries;
    private final DoctorDailyPerformanceRepository performances;
    private final ReportingProperties properties;

    // ================================================================== đợt khám

    @Transactional
    public void onVisitCheckedIn(VisitCheckedInEvent e) {
        if (visitFacts.existsById(e.visitId())) {
            return;   // sự kiện phát lại với eventId khác: giữ dòng đã có
        }
        visitFacts.save(VisitFact.builder()
                .visitId(e.visitId())
                .visitCode(e.visitCode())
                .businessDate(businessDate(e.occurredAt()))
                .checkedInAt(e.occurredAt())
                .currentStatus(VisitFact.WAITING)
                .emergency(e.emergency())
                .petId(e.petId())
                .petName(orElse(e.petName(), UNKNOWN))
                .speciesCode(orElse(e.speciesCode(), "UNKNOWN"))
                .ownerId(e.ownerId() == null ? NIL_UUID : e.ownerId())
                .ownerName(orElse(e.ownerName(), UNKNOWN))
                .doctorId(e.doctorId())
                .doctorName(e.doctorName())
                .build());
    }

    @Transactional
    public void onVisitCompleted(VisitCompletedEvent e) {
        VisitFact fact = requireVisit(e.visitId());
        if (VisitFact.COMPLETED.equals(fact.getCurrentStatus())) {
            return;
        }
        if (e.doctorId() != null) {
            fact.setDoctorId(e.doctorId());
            fact.setDoctorName(e.doctorName());
        }
        fact.setCurrentStatus(VisitFact.COMPLETED);
        fact.setCompletedAt(e.occurredAt());
        visitFacts.save(fact);
        if (fact.getDoctorId() != null) {
            performance(businessDate(e.occurredAt()), fact.getDoctorId(), fact.getDoctorName(), 1, 0, fact.isEmergency() ? 1 : 0, BigDecimal.ZERO);
        }
    }

    @Transactional
    public void onVisitCancelled(VisitCancelledEvent e) {
        VisitFact fact = requireVisit(e.visitId());
        if (VisitFact.CANCELLED.equals(fact.getCurrentStatus())) {
            return;
        }
        if (e.doctorId() != null && fact.getDoctorId() == null) {
            fact.setDoctorId(e.doctorId());
        }
        fact.setCurrentStatus(VisitFact.CANCELLED);
        visitFacts.save(fact);
        if (fact.getDoctorId() != null) {
            performance(businessDate(e.occurredAt()), fact.getDoctorId(), fact.getDoctorName(), 0, 1, 0, BigDecimal.ZERO);
        }
    }

    // ================================================================== doanh thu

    @Transactional
    public void onServiceInvoicePaid(ServiceInvoicePaidEvent e) {
        VisitFact visit = visitFacts.findById(e.visitId()).orElse(null);   // chỉ để lấy tên Bác sĩ; thiếu thì để trống
        UUID doctorId = e.doctorId() != null ? e.doctorId() : visit == null ? null : visit.getDoctorId();
        // tên Bác sĩ theo sự kiện thu (không phụ thuộc sự kiện tiếp đón có tới trước hay không), thiếu thì lấy ở visit_facts
        String doctorName = e.doctorName() != null && !e.doctorName().isBlank() ? e.doctorName() : doctorName(visit, doctorId);
        Instant at = e.paidAt() != null ? e.paidAt() : e.occurredAt();
        LocalDate date = businessDate(at);
        for (ServiceInvoicePaidEvent.Line line : e.lines()) {
            if (revenueFacts.existsBySourceInvoiceIdAndItemIdAndEntryType(line.invoiceId(), line.serviceId(), RevenueEntryType.SALE)) {
                continue;
            }
            BigDecimal before = money(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
            BigDecimal discount = money(line.discountAmount() == null ? BigDecimal.ZERO : line.discountAmount());
            BigDecimal net = line.payableAmount() != null ? money(line.payableAmount())
                    : money(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())).multiply(BigDecimal.ONE.add(line.vatRate())).subtract(discount));
            BigDecimal vat = net.add(discount).subtract(before);   // before + vat - discount = net (khớp VIEW của clinical)
            write(RevenueFact.builder()
                    .revenueType(RevenueType.SERVICE).entryType(RevenueEntryType.SALE)
                    .sourceInvoiceId(line.invoiceId()).invoiceCode(line.invoiceCode())
                    .itemId(line.serviceId()).itemCode(line.serviceCode()).itemName(line.serviceName())
                    .categoryCode(line.category()).categoryName(orElse(line.categoryName(), line.category()))
                    .visitId(e.visitId()).doctorId(doctorId).doctorName(doctorName).cashierId(e.cashierId())
                    .businessDate(date).occurredAt(at).quantity(line.quantity())
                    .amountBeforeVat(before).vatAmount(vat).discountAmount(discount).netAmount(net)
                    .build());
        }
    }

    @Transactional
    public void onServiceInvoiceRefunded(ServiceInvoiceRefundedEvent e) {
        List<RevenueFact> sales = revenueFacts.findBySourceInvoiceIdAndRevenueTypeAndEntryType(e.invoiceId(), RevenueType.SERVICE, RevenueEntryType.SALE);
        if (sales.isEmpty()) {
            throw new IllegalStateException("Hoàn tiền hóa đơn " + e.invoiceCode() + " nhưng chưa thấy dòng thu tương ứng (sự kiện thu đến chậm)");
        }
        for (RevenueFact sale : sales) {
            if (revenueFacts.existsBySourceInvoiceIdAndItemIdAndEntryType(sale.getSourceInvoiceId(), sale.getItemId(), RevenueEntryType.REFUND)) {
                continue;
            }
            if (e.amount() != null && e.amount().compareTo(sale.getNetAmount()) != 0) {
                log.warn("Hoàn tiền hóa đơn {}: số hoàn {} khác số đã thu {}; báo cáo ghi theo số đã thu", e.invoiceCode(), e.amount(), sale.getNetAmount());
            }
            write(RevenueFact.builder()
                    .revenueType(RevenueType.SERVICE).entryType(RevenueEntryType.REFUND)
                    .sourceInvoiceId(sale.getSourceInvoiceId()).invoiceCode(sale.getInvoiceCode())
                    .itemId(sale.getItemId()).itemCode(sale.getItemCode()).itemName(sale.getItemName())
                    .categoryCode(sale.getCategoryCode()).categoryName(sale.getCategoryName())
                    .visitId(sale.getVisitId()).doctorId(sale.getDoctorId()).doctorName(sale.getDoctorName()).cashierId(e.actorUserId())
                    .businessDate(businessDate(e.occurredAt())).occurredAt(e.occurredAt()).quantity(-sale.getQuantity())
                    .amountBeforeVat(sale.getAmountBeforeVat().negate()).vatAmount(sale.getVatAmount().negate())
                    .discountAmount(sale.getDiscountAmount().negate()).netAmount(sale.getNetAmount().negate())
                    .build());
        }
    }

    @Transactional
    public void onMedicineInvoicePaid(MedicineInvoicePaidEvent e) {
        if (e.lines() == null || e.lines().isEmpty()) {
            log.warn("MedicineInvoicePaidEvent {} không mang dòng thuốc (tin nhắn phát trước bước 7.1): không ghi doanh thu", e.invoiceCode());
            return;
        }
        VisitFact visit = e.visitId() == null ? null : visitFacts.findById(e.visitId()).orElse(null);
        UUID doctorId = visit == null ? null : visit.getDoctorId();
        String doctorName = doctorName(visit, doctorId);
        Instant at = e.paidAt() != null ? e.paidAt() : e.occurredAt();
        LocalDate date = businessDate(at);

        // Giảm giá của cả hóa đơn chia cho các dòng theo tỷ lệ giá trị (dòng cuối nhận phần dư); số phải trả khớp tuyệt đối payableAmount của sự kiện
        List<MedicineInvoicePaidEvent.Line> lines = e.lines();
        BigDecimal invoiceDiscount = e.discountAmount() == null ? BigDecimal.ZERO : e.discountAmount();
        BigDecimal totalGross = BigDecimal.ZERO;
        for (MedicineInvoicePaidEvent.Line l : lines) {
            totalGross = totalGross.add(gross(l));
        }
        List<RevenueFact> built = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal netSoFar = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            MedicineInvoicePaidEvent.Line l = lines.get(i);
            boolean last = i == lines.size() - 1;
            BigDecimal discount;
            if (last) {
                discount = invoiceDiscount.subtract(allocated);
            } else if (totalGross.signum() == 0) {
                discount = BigDecimal.ZERO;
            } else {
                discount = money(invoiceDiscount.multiply(gross(l)).divide(totalGross, 6, RoundingMode.HALF_UP));
            }
            allocated = allocated.add(discount);
            BigDecimal before = money(l.unitPrice().multiply(BigDecimal.valueOf(l.quantity())));
            BigDecimal net = money(gross(l)).subtract(discount);
            if (last && e.payableAmount() != null) {
                net = money(e.payableAmount()).subtract(netSoFar);   // dòng cuối hấp thụ chênh lệch làm tròn
            }
            netSoFar = netSoFar.add(net);
            built.add(RevenueFact.builder()
                    .revenueType(RevenueType.MEDICINE).entryType(RevenueEntryType.SALE)
                    .sourceInvoiceId(e.invoiceId()).invoiceCode(e.invoiceCode())
                    .itemId(l.medicineId()).itemCode(l.medicineCode()).itemName(l.medicineName())
                    .categoryCode(orElse(l.categoryCode(), "UNKNOWN")).categoryName(orElse(l.categoryName(), orElse(l.categoryCode(), UNKNOWN)))
                    .visitId(e.visitId() == null ? NIL_UUID : e.visitId()).doctorId(doctorId).doctorName(doctorName).cashierId(e.actorUserId())
                    .businessDate(date).occurredAt(at).quantity(l.quantity())
                    .amountBeforeVat(before).vatAmount(net.add(discount).subtract(before)).discountAmount(discount).netAmount(net)
                    .build());
        }
        for (RevenueFact fact : built) {
            if (!revenueFacts.existsBySourceInvoiceIdAndItemIdAndEntryType(fact.getSourceInvoiceId(), fact.getItemId(), RevenueEntryType.SALE)) {
                write(fact);
            }
        }
    }

    // ================================================================== snapshot tên

    @Transactional
    public void onUserProfileUpdated(UserProfileUpdatedEvent e) {
        if (e.fullName() == null || e.fullName().isBlank()) {
            return;
        }
        int rows = visitFacts.renameDoctor(e.userId(), e.fullName()) + visitFacts.renameOwner(e.userId(), e.fullName())
                + revenueFacts.renameDoctor(e.userId(), e.fullName()) + performances.renameDoctor(e.userId(), e.fullName());
        if (rows > 0) {
            log.info("Cập nhật tên {} trên {} dòng báo cáo", e.userId(), rows);
        }
    }

    @Transactional
    public void onPetUpdated(PetUpdatedEvent e) {
        if (e.petName() == null || e.petName().isBlank() || e.ownerId() == null) {
            return;   // sự kiện thiếu dữ liệu: không ghi đè cột NOT NULL bằng null
        }
        int rows = visitFacts.updatePet(e.petId(), e.petName(), e.ownerId(), orElse(e.ownerName(), UNKNOWN));
        if (rows > 0) {
            log.info("Cập nhật thú cưng {} trên {} đợt khám trong báo cáo", e.petId(), rows);
        }
    }

    // ================================================================== helper

    private void write(RevenueFact fact) {
        revenueFacts.save(fact);
        boolean sale = fact.getEntryType() == RevenueEntryType.SALE;
        summaries.accumulate(fact.getBusinessDate(), fact.getRevenueType().name(), fact.getCategoryCode(), fact.getCategoryName(), sale ? 1 : -1,
                fact.getAmountBeforeVat(), fact.getVatAmount(), fact.getDiscountAmount(), fact.getNetAmount());
        if (fact.getRevenueType() == RevenueType.SERVICE && fact.getDoctorId() != null) {
            performance(fact.getBusinessDate(), fact.getDoctorId(), orElse(fact.getDoctorName(), UNKNOWN), 0, 0, 0, fact.getNetAmount());
        }
    }

    private void performance(LocalDate date, UUID doctorId, String doctorName, int completed, int cancelled, int emergency, BigDecimal revenue) {
        performances.accumulate(date, toBytes(doctorId), orElse(doctorName, UNKNOWN), completed, cancelled, emergency, revenue);
    }

    private VisitFact requireVisit(UUID visitId) {
        Optional<VisitFact> fact = visitFacts.findById(visitId);
        return fact.orElseThrow(() -> new IllegalStateException("Chưa có visit_facts cho đợt khám " + visitId + " (sự kiện tiếp đón đến chậm)"));
    }

    private static String doctorName(VisitFact visit, UUID doctorId) {
        if (doctorId == null) {
            return null;
        }
        return visit != null && doctorId.equals(visit.getDoctorId()) ? visit.getDoctorName() : null;
    }

    private static BigDecimal gross(MedicineInvoicePaidEvent.Line l) {
        return l.unitPrice().multiply(BigDecimal.valueOf(l.quantity())).multiply(BigDecimal.ONE.add(l.vatRate()));
    }

    LocalDate businessDate(Instant instant) {
        return instant.atZone(properties.zoneId()).toLocalDate();
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** UUID -> 16 byte big-endian, đúng cách Hibernate ghi UUID vào BINARY(16). */
    static byte[] toBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
