package com.vetclinic.appointment.entity;

import com.vetclinic.appointment.constant.AppointmentStatus;
import com.vetclinic.appointment.constant.BookingChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Lịch hẹn khám: đúng 14 cột của docs v4.0 (UUIDv7). {@code ownerId} và 3 cột {@code *_snapshot} (tên thú cưng, tên + SĐT chủ nuôi) giúp liệt
 * kê lịch theo chủ nuôi / theo ngày không phải gọi sang clinical hay auth (tránh N+1 chéo service); chụp lúc đặt lịch và cập nhật bất đồng
 * bộ khi nhận {@code UserProfileUpdatedEvent} / {@code PetUpdatedEvent}. Người duyệt, người hủy, lý do hủy lấy từ appointment_status_histories.
 */
@Entity
@Table(name = "appointments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Appointment {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "appointment_code", nullable = false, unique = true, length = 50)
    private String appointmentCode;

    /** Khóa ngoại logic -> pet_clinic_clinical_db.pets(id) */
    @Column(name = "pet_id", nullable = false)
    private UUID petId;

    /** Khóa ngoại logic -> pet_clinic_auth_db.users(id): chủ nuôi tại thời điểm đặt lịch. */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "pet_name_snapshot", nullable = false, length = 100)
    private String petNameSnapshot;

    @Column(name = "owner_name_snapshot", nullable = false, length = 150)
    private String ownerNameSnapshot;

    @Column(name = "owner_phone_snapshot", nullable = false, length = 20)
    private String ownerPhoneSnapshot;

    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "booking_channel", nullable = false, length = 20)
    @Builder.Default
    private BookingChannel bookingChannel = BookingChannel.ONLINE;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private AppointmentStatus status = AppointmentStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Khóa lạc quan: hai người cùng duyệt / hủy một lịch thì người sau nhận SYS_1005. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
