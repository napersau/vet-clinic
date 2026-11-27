package com.vetclinic.clinical.entity;

import jakarta.persistence.Version;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import java.time.Instant;

@Entity
@Table(name = "visit_service_details")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisitServiceDetail {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "visit_id", nullable = false)
    private UUID visitId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private ClinicService service;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private ServiceDetailStatus status = ServiceDetailStatus.PENDING;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // Dịch vụ XN / CĐHA vào hàng đợi qua queue_ticket_items sau khi khách nộp tiền (17/11/2026); bảng này không có cột hàng đợi.
    // Giá/VAT KHÔNG nằm ở đây mà ở service_invoices (1-1 với dòng chỉ định này).
}
