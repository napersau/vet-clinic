package com.vetclinic.clinical.entity;

import com.vetclinic.clinical.constant.VisitStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Đợt khám: CHỈ thông tin hành chính do Lễ tân nhập lúc tiếp đón (docs v4.0 mục 22). Sinh hiệu nằm ở {@link VitalSign},
 * nội dung chuyên môn ở {@link MedicalRecord}, hàng đợi ở queue_tickets.
 */
@Entity
@Table(name = "pet_visits")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PetVisit {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "visit_code", nullable = false, unique = true, length = 50)
    private String visitCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pet_id", nullable = false)
    private Pet pet;

    /** Khóa ngoại logic -> pet_clinic_appointment_db.appointments(id). NULL = khách vãng lai. */
    @Column(name = "appointment_id", unique = true)
    private UUID appointmentId;

    /** Khóa ngoại logic -> auth.users(id): Bác sĩ phụ trách (do Lễ tân phân công). */
    @Column(name = "doctor_id")
    private UUID doctorId;

    /** Tên Bác sĩ chụp lúc phân công (tránh N+1 khi liệt kê); cập nhật bằng UserProfileUpdatedEvent. */
    @Column(name = "doctor_name_snapshot", length = 150)
    private String doctorNameSnapshot;

    @Column(name = "receptionist_id", nullable = false)
    private UUID receptionistId;

    @Column(name = "is_emergency", nullable = false)
    private boolean emergency;

    /** Lý do đến khám do chủ nuôi khai với Lễ tân. */
    @Column(name = "reason_for_visit", columnDefinition = "TEXT")
    private String reasonForVisit;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private VisitStatus status = VisitStatus.WAITING;

    @Column(name = "checked_in_at", nullable = false)
    private Instant checkedInAt;

    // Thông tin xếp hàng (số phiếu, thứ tự, giờ gọi) nằm ở queue_tickets (SM-07), không lưu trên hồ sơ khám.

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
