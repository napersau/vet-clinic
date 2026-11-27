package com.vetclinic.clinical.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Hồ sơ khám chuyên môn của Bác sĩ, 1-1 với đợt khám (docs v4.0 mục 24). Tạo khi Bác sĩ bắt đầu khám (T02);
 * khóa chỉnh sửa từ lúc chốt chuyên môn (đợt khám rời IN_PROGRESS). Dùng {@code @Version} để hai thiết bị cùng sửa không ghi đè nhau.
 */
@Entity
@Table(name = "medical_records")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicalRecord {
    /** Cũng là khóa ngoại tới pet_visits(id). */
    @Id
    @Column(name = "visit_id")
    private UUID visitId;

    /** Bệnh sử, triệu chứng chủ nuôi kể. */
    @Column(name = "anamnesis", columnDefinition = "TEXT")
    private String anamnesis;

    /** Kết quả khám lâm sàng. */
    @Column(name = "clinical_findings", columnDefinition = "TEXT")
    private String clinicalFindings;

    /** Chẩn đoán sơ bộ (giai đoạn 1). */
    @Column(name = "preliminary_diagnosis", columnDefinition = "TEXT")
    private String preliminaryDiagnosis;

    /** Chẩn đoán xác định (giai đoạn 2, sau cận lâm sàng); bắt buộc khi chốt ca. */
    @Column(name = "final_diagnosis", columnDefinition = "TEXT")
    private String finalDiagnosis;

    @Column(name = "treatment_plan", columnDefinition = "TEXT")
    private String treatmentPlan;

    @Column(name = "doctor_advice", columnDefinition = "TEXT")
    private String doctorAdvice;

    /** Hẹn tái khám. */
    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
