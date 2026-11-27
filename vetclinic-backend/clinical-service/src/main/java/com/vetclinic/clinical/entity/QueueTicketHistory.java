package com.vetclinic.clinical.entity;

import jakarta.persistence.CollectionTable;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.TicketStatus;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lịch sử phiếu xếp hàng ĐÃ KẾT THÚC (docs v4.0 mục 34-35). Phiếu đang chờ / đang phục vụ sống trên Redis; khi phiếu DONE / CANCELLED, một dòng được
 * ghi ở đây trong CÙNG giao dịch với thay đổi nghiệp vụ, sau commit mới xóa phiếu khỏi Redis. {@code ticket_id UNIQUE} chặn ghi trùng khi thử lại.
 * Dùng cho tra cứu, thống kê thời gian chờ ({@code started_at - issued_at}) và dựng lại Redis (bước 6.3).
 */
@Entity
@Table(name = "queue_ticket_histories")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueTicketHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UUIDv7 sinh lúc phát phiếu trên Redis. */
    @Column(name = "ticket_id", nullable = false, unique = true)
    private UUID ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "queue_type", nullable = false, length = 20)
    private QueueType queueType;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "ticket_no", nullable = false)
    private int ticketNo;

    @Column(name = "visit_id", nullable = false)
    private UUID visitId;

    @Column(name = "doctor_id")
    private UUID doctorId;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "priority", nullable = false)
    private int priority;

    /** DONE | CANCELLED (giá trị cuối của {@link TicketStatus}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "final_status", nullable = false, length = 20)
    private TicketStatus finalStatus;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "issued_by")
    private UUID issuedBy;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "called_count", nullable = false)
    private int calledCount;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false)
    private Instant endedAt;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    /** Các dịch vụ XN / CĐHA thuộc phiếu (queue_ticket_history_items). */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "queue_ticket_history_items", joinColumns = @JoinColumn(name = "history_id"))
    @Column(name = "visit_service_detail_id", nullable = false)
    @Builder.Default
    private List<UUID> detailIds = new ArrayList<>();
}
