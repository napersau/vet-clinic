package com.vetclinic.clinical.entity;

import com.vetclinic.clinical.constant.QueueType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Nhóm dịch vụ (bảng danh mục thay ENUM {@code services.category}). Hành vi của một dịch vụ do nhóm quyết định, đọc từ dữ liệu:
 * <ul>
 *   <li>{@code queueType}: DOCTOR | LABORATORY | IMAGING = vào hàng đợi tương ứng; NULL = làm ngay tại phòng Bác sĩ, không xếp hàng riêng;</li>
 *   <li>{@code requiresPrepayment}: TRUE = thu tiền xong mới phát phiếu xếp hàng (khám, XN, CĐHA); FALSE = thu khi chốt ca.</li>
 * </ul>
 */
@Entity
@Table(name = "service_categories")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceCategory {

    /** Mã nhóm mà mã nguồn còn nhắc tên (seed của docs); mọi hành vi khác đọc từ {@code queueType} / {@code requiresPrepayment}. */
    public static final String EXAMINATION = "EXAMINATION";
    public static final String LABORATORY = "LABORATORY";
    public static final String IMAGING = "IMAGING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short id;

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "queue_type", length = 20)
    private String queueType;

    @Column(name = "requires_prepayment", nullable = false)
    private boolean requiresPrepayment;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    public boolean is(String categoryCode) {
        return code.equals(categoryCode);
    }

    /** Loại hàng đợi của nhóm; null nếu dịch vụ làm tại phòng. */
    public QueueType queueTypeOrNull() {
        return queueType == null ? null : QueueType.valueOf(queueType);
    }

    /** Cận lâm sàng: đi qua hàng đợi phòng Xét nghiệm / CĐHA. */
    public boolean isParaclinical() {
        QueueType type = queueTypeOrNull();
        return type == QueueType.LABORATORY || type == QueueType.IMAGING;
    }

    /** Làm tại phòng khám (FR-30): không có hàng đợi riêng; bác sĩ chỉ định rồi bấm "Đã thực hiện". */
    public boolean isInRoom() {
        return queueType == null;
    }
}
