package com.vetclinic.clinical.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Một lần đo sinh hiệu (docs v4.0 mục 23): một đợt khám có thể đo nhiều lần (đầu ca, sau xét nghiệm, trước chốt...).
 * Mỗi lần đo là một dòng MỚI, không sửa dòng cũ; số liệu "hiện tại" là dòng ghi gần nhất.
 */
@Entity
@Table(name = "vital_signs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VitalSign {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "visit_id", nullable = false)
    private UUID visitId;

    @Column(name = "weight_kg", precision = 5, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "temperature_c", precision = 4, scale = 1)
    private BigDecimal temperatureC;

    @JdbcTypeCode(SqlTypes.SMALLINT)   // cột SMALLINT: Hibernate validate không chấp nhận Integer mặc định
    @Column(name = "heart_rate_bpm")
    private Integer heartRateBpm;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "respiratory_rate_bpm")
    private Integer respiratoryRateBpm;

    /** Thang 1 - 9. */
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "body_condition_score")
    private Integer bodyConditionScore;

    @Column(name = "mucous_membrane_color", length = 30)
    private String mucousMembraneColor;

    @Column(name = "capillary_refill_sec", precision = 3, scale = 1)
    private BigDecimal capillaryRefillSec;

    @Column(name = "notes", length = 255)
    private String notes;

    /** Khóa ngoại logic -> auth.users(id). */
    @Column(name = "recorded_by", nullable = false)
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}
