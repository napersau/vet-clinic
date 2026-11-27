package com.vetclinic.clinical.entity;

import org.hibernate.annotations.UuidGenerator;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;

@Entity
@Table(name = "imaging_results")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImagingResult {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "visit_service_detail_id", nullable = false, unique = true)
    private UUID visitServiceDetailId;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "conclusion", nullable = false, columnDefinition = "TEXT")
    private String conclusion;

    @Column(name = "performed_by", nullable = false)
    private UUID performedBy;

    @CreationTimestamp
    @Column(name = "performed_at", nullable = false, updatable = false)
    private Instant performedAt;
}
