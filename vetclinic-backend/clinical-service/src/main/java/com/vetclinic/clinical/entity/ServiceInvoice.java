package com.vetclinic.clinical.entity;

import org.hibernate.annotations.UuidGenerator;
import java.util.UUID;
import com.vetclinic.clinical.constant.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Hóa đơn RIÊNG cho từng dịch vụ (v3.0): mỗi dòng chỉ định {@link VisitServiceDetail} có ĐÚNG MỘT hóa đơn (UNIQUE), tạo cùng
 * giao dịch với chỉ định. Dịch vụ CANCELLED thì hóa đơn CANCELLED (BR-33).
 * <p>
 * Tiền KHÔNG lưu: số phải trả = quantity × unit_price × (1 + vat_rate) − discount_amount, tính bằng VIEW
 * {@code v_service_invoice_amounts} hoặc trong code. unit_price / vat_rate là SNAPSHOT lúc chỉ định, bất biến dù Admin đổi bảng giá.
 */
@Entity
@Table(name = "service_invoices")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceInvoice {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /** SINV-YYYY-XXXX, sinh từ id sau khi INSERT (không dùng count + 1). */
    @Column(name = "invoice_code", nullable = false, unique = true, length = 50)
    private String invoiceCode;

    /** Khóa ngoại vật lý tới visit_service_details(id), UNIQUE: quan hệ 1-1. */
    @Column(name = "visit_service_detail_id", nullable = false, unique = true)
    private UUID visitServiceDetailId;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "vat_rate", nullable = false, precision = 4, scale = 2)
    private BigDecimal vatRate;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO.setScale(2);

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    @Builder.Default
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    /** Khóa ngoại logic -> auth.users(id), gán khi thu tiền. */
    @Column(name = "cashier_id")
    private UUID cashierId;

    @Column(name = "paid_at")
    private Instant paidAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Khóa lạc quan: hai thu ngân cùng xác nhận thì chỉ một giao dịch thành công, người kia nhận SYS_1005 (EX-16). */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
