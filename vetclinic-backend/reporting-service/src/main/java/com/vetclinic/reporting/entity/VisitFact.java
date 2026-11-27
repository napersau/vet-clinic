package com.vetclinic.reporting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Một dòng / đợt khám (schema v4.0 mục 48). Tạo khi nhận VisitCheckedInEvent; cập nhật khi hoàn tất / hủy / đổi tên. Khóa = visit_id (không tự sinh). */
@Entity
@Table(name = "visit_facts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisitFact {

    public static final String WAITING = "WAITING";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @Column(name = "visit_id")
    private UUID visitId;

    @Column(name = "visit_code", nullable = false, length = 50)
    private String visitCode;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "checked_in_at", nullable = false)
    private Instant checkedInAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** WAITING | COMPLETED | CANCELLED (ba mốc báo cáo cần; không lưu ENUM). */
    @Column(name = "current_status", nullable = false, length = 20)
    private String currentStatus;

    @Column(name = "is_emergency", nullable = false)
    private boolean emergency;

    @Column(name = "pet_id", nullable = false)
    private UUID petId;

    @Column(name = "pet_name", nullable = false, length = 100)
    private String petName;

    @Column(name = "species_code", nullable = false, length = 30)
    private String speciesCode;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "owner_name", nullable = false, length = 150)
    private String ownerName;

    @Column(name = "doctor_id")
    private UUID doctorId;

    @Column(name = "doctor_name", length = 150)
    private String doctorName;
}
