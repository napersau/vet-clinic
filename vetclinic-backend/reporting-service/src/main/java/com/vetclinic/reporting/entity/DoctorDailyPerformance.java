package com.vetclinic.reporting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Hiệu suất Bác sĩ theo ngày (schema v4.0 mục 51). CHỈ ĐỌC trong ứng dụng: cộng dồn bằng upsert nguyên tử ({@code DoctorDailyPerformanceRepository}). */
@Entity
@Table(name = "doctor_daily_performances")
@IdClass(DoctorDailyPerformanceId.class)
@Getter
@NoArgsConstructor
public class DoctorDailyPerformance {

    @Id
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Id
    @Column(name = "doctor_id", nullable = false)
    private UUID doctorId;

    @Column(name = "doctor_name", nullable = false, length = 150, insertable = false, updatable = false)
    private String doctorName;

    @Column(name = "completed_visits", nullable = false, insertable = false, updatable = false)
    private int completedVisits;

    @Column(name = "cancelled_visits", nullable = false, insertable = false, updatable = false)
    private int cancelledVisits;

    @Column(name = "emergency_visits", nullable = false, insertable = false, updatable = false)
    private int emergencyVisits;

    @Column(name = "service_net_revenue", nullable = false, precision = 16, scale = 2, insertable = false, updatable = false)
    private BigDecimal serviceNetRevenue;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
