package com.vetclinic.billing.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Thông tin thuốc và quy cách (docs v4.0 mục 39): KHÔNG chứa giá (lịch sử giá ở {@link MedicinePrice}, đọc qua MedicinePriceBook) và KHÔNG
 * chứa tồn kho (dòng tồn kho 1-1 ở {@link MedicineStock}, bị khóa / cập nhật liên tục khi bán). Thuốc đã phát sinh giao dịch không xóa
 * cứng: ngừng kinh doanh bằng {@code active = false}.
 * <p>
 * {@link #getStockQuantity()} chỉ ĐỌC số tồn từ dòng tồn kho đã nạp; tồn kho CHỈ đổi bằng câu UPDATE nguyên tử trong MedicineStockRepository,
 * không bao giờ gán số rồi save().
 */
@Entity
@Table(name = "medicines")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Medicine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private MedicineCategory category;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /** Dạng bào chế: viên nén, lọ tiêm đông khô... */
    @Column(name = "dosage_form", nullable = false, length = 50)
    private String dosageForm;

    /** Hàm lượng tổng hiển thị: 250mg. */
    @Column(name = "strength", length = 50)
    private String strength;

    /** Đơn vị bán nhỏ nhất: viên, lọ, ống. */
    @Column(name = "base_unit", nullable = false, length = 30)
    private String baseUnit;

    /** Quy cách đóng gói: Hộp 10 vỉ x 10 viên. */
    @Column(name = "packaging_spec", nullable = false, length = 150)
    private String packagingSpec;

    @Column(name = "manufacturer", length = 150)
    private String manufacturer;

    @Column(name = "country_of_origin", length = 100)
    private String countryOfOrigin;

    /** Số đăng ký lưu hành. */
    @Column(name = "registration_number", length = 50)
    private String registrationNumber;

    @Column(name = "storage_conditions")
    private String storageConditions;

    @Column(name = "requires_prescription", nullable = false)
    @Builder.Default
    private boolean requiresPrescription = true;

    @Column(name = "min_stock_alert", nullable = false)
    @Builder.Default
    private int minStockAlert = 10;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "medicine", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 50)
    @Builder.Default
    private List<MedicineIngredient> ingredients = new ArrayList<>();

    /** Dòng tồn kho 1-1 (phía không giữ khóa ngoại). REFRESH lan theo để {@code entityManager.refresh(medicine)} đọc lại tồn thật sau khi trừ kho. */
    @OneToOne(mappedBy = "medicine", fetch = FetchType.LAZY, cascade = CascadeType.REFRESH)
    private MedicineStock stock;

    public void addIngredient(MedicineIngredient ingredient) {
        ingredient.setMedicine(this);
        ingredients.add(ingredient);
    }

    /** Tồn kho hiện tại (0 nếu chưa có dòng tồn kho). */
    public int getStockQuantity() {
        return stock == null ? 0 : stock.getQuantityOnHand();
    }

    /** Đơn vị bán nhỏ nhất (tên cũ {@code unit} ở giao diện / thông điệp). */
    public String getUnit() {
        return baseUnit;
    }

    /** Hoạt chất gom thành một chuỗi hiển thị: "Amoxicillin + Clavulanic acid". */
    public String getActiveIngredient() {
        if (ingredients == null || ingredients.isEmpty()) {
            return null;
        }
        return ingredients.stream().map(MedicineIngredient::getIngredientName).collect(Collectors.joining(" + "));
    }
}
