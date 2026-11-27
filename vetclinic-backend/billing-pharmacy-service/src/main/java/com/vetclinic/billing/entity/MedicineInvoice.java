package com.vetclinic.billing.entity;

import com.vetclinic.billing.constant.PaymentStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Hóa đơn thuốc (SM-06, docs v4.0 mục 43): NHÁP tự tạo từ {@code PrescriptionCreatedEvent} khi Bác sĩ chốt ca, tối đa một hóa đơn cho mỗi đơn
 * thuốc ({@code prescription_id UNIQUE}, kể cả hóa đơn đã hủy). Khách mua thì Thu ngân sửa số lượng rồi thu; khách mua ngoài thì hủy. Thông tin
 * khách / thú cưng / Bác sĩ chụp từ sự kiện để in hóa đơn mà không gọi clinical / auth. Số tiền KHÔNG lưu:
 * {@code SUM(qty * unit_price * (1 + vat_rate)) - discount_amount} (VIEW v_medicine_invoice_amounts,
 * xem {@link com.vetclinic.billing.service.MedicineInvoiceAmounts}). {@code @Version} chặn thu tiền trùng (EX-16).
 */
@Entity
@Table(name = "medicine_invoices")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicineInvoice {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "invoice_code", nullable = false, unique = true, length = 50)
    private String invoiceCode;

    /** Khóa ngoại logic -> clinical.prescriptions(id). */
    @Column(name = "prescription_id", nullable = false, unique = true)
    private UUID prescriptionId;

    /** Khóa ngoại logic -> clinical.pet_visits(id): tra cứu hóa đơn theo đợt khám. */
    @Column(name = "visit_id", nullable = false)
    private UUID visitId;

    @Column(name = "customer_name_snapshot", nullable = false, length = 150)
    private String customerNameSnapshot;

    @Column(name = "customer_phone_snapshot", nullable = false, length = 20)
    private String customerPhoneSnapshot;

    @Column(name = "pet_name_snapshot", nullable = false, length = 100)
    private String petNameSnapshot;

    @Column(name = "doctor_name_snapshot", nullable = false, length = 150)
    private String doctorNameSnapshot;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    @Builder.Default
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    /** Khóa ngoại logic -> auth.users(id): thu ngân, gán khi thu tiền. */
    @Column(name = "cashier_id")
    private UUID cashierId;

    @Column(name = "paid_at")
    private Instant paidAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @Builder.Default
    private List<MedicineInvoiceItem> items = new ArrayList<>();

    public void addItem(MedicineInvoiceItem item) {
        item.setInvoice(this);
        items.add(item);
    }

    public Optional<MedicineInvoiceItem> findItemByMedicineId(UUID medicineId) {
        return items.stream().filter(i -> i.getMedicine().getId().equals(medicineId)).findFirst();
    }
}
