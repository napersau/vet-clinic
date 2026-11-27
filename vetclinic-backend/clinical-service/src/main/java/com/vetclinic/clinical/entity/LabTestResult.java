package com.vetclinic.clinical.entity;

import java.util.UUID;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "lab_test_results")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabTestResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "visit_service_detail_id", nullable = false)
    private UUID visitServiceDetailId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lab_test_item_id", nullable = false)
    private LabTestItem labTestItem;

    @Column(name = "result_value", nullable = false, precision = 8, scale = 2)
    private BigDecimal resultValue;

    /** Snapshot khoảng tham chiếu của loài tại thời điểm xét nghiệm (BR-24). */
    @Column(name = "min_value_snapshot", nullable = false, precision = 8, scale = 2)
    private BigDecimal minValueSnapshot;

    @Column(name = "max_value_snapshot", nullable = false, precision = 8, scale = 2)
    private BigDecimal maxValueSnapshot;

    @Column(name = "performed_by", nullable = false)
    private UUID performedBy;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
