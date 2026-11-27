package com.vetclinic.clinical.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Một thuốc trong đơn (docs v4.0 mục 33). {@code medicineId} là khóa ngoại logic sang danh mục thuốc của billing-pharmacy; tên và đơn vị được
 * chụp lại lúc kê để bệnh án luôn đọc được mà không gọi chéo service. Mỗi thuốc một dòng {@code UNIQUE(prescription_id, medicine_id)}.
 */
@Entity
@Table(name = "prescription_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrescriptionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prescription_id", nullable = false)
    private Prescription prescription;

    @Column(name = "medicine_id", nullable = false)
    private UUID medicineId;

    @Column(name = "medicine_name_snapshot", nullable = false, length = 150)
    private String medicineNameSnapshot;

    @Column(name = "unit_snapshot", nullable = false, length = 30)
    private String unitSnapshot;

    /** Số lượng Bác sĩ kê (billing lấy làm số mua mặc định; khách được mua nhiều / ít hơn). */
    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "dosage", nullable = false, length = 100)
    private String dosage;

    @Column(name = "frequency", nullable = false, length = 100)
    private String frequency;

    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Column(name = "instructions", columnDefinition = "TEXT")
    private String instructions;
}
