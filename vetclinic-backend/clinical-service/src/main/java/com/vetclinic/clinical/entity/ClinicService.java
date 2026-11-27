package com.vetclinic.clinical.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Thông tin dịch vụ, KHÔNG chứa giá (giá ở {@link ServicePrice}, lấy qua {@code ServicePriceBook}). Không xóa cứng: ngừng cung cấp = {@code active = false}.
 * Chính sách thu trước và loại hàng đợi đọc từ {@link ServiceCategory}.
 */
@Entity
@Table(name = "services")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClinicService {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    // EAGER: bảng nhóm chỉ vài dòng và gần như mọi chỗ dùng dịch vụ đều cần nhóm (open-in-view tắt nên không lazy-load được)
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private ServiceCategory category;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Đơn vị tính: lần, mũi, tư thế... */
    @Column(name = "unit", nullable = false, length = 30)
    @Builder.Default
    private String unit = "Lần";

    @Column(name = "estimated_minutes")
    private Short estimatedMinutes;

    /** Dặn dò chuẩn bị, ví dụ nhịn ăn 8 giờ trước khi siêu âm. */
    @Column(name = "preparation_notes")
    private String preparationNotes;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Chính sách thu trước của dịch vụ = của nhóm (BR-35). */
    public boolean isRequiresPrepayment() {
        return category.isRequiresPrepayment();
    }
}
