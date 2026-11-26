package com.vetclinic.appointment.dto.response;

import com.vetclinic.appointment.constant.AppointmentStatus;
import com.vetclinic.appointment.constant.BookingChannel;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record AppointmentResponse(
        UUID id,
        String appointmentCode,
        UUID petId,
        UUID ownerId,
        LocalDate appointmentDate,
        LocalTime startTime,
        LocalTime endTime,
        String reason,
        BookingChannel bookingChannel,
        AppointmentStatus status,
        String cancelReason,
        UUID confirmedBy,
        Instant confirmedAt,
        UUID cancelledBy,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt,
        String petName,
        String ownerName,
        String ownerPhone
) {

    /**
     * Từ v4.0 bảng appointments có {@code owner_id} và 3 cột snapshot nên {@code ownerId, petName, ownerName, ownerPhone} đọc thẳng từ
     * bảng (không còn gọi clinical / auth). Các trường dưới đây KHÔNG lưu mà được suy ra: endTime = startTime + độ dài khung;
     * cancelReason / confirmedBy / confirmedAt / cancelledBy / cancelledAt / updatedAt lấy từ appointment_status_histories
     * (AppointmentEnricher).
     */
    public AppointmentResponse withEndTime(LocalTime endTime) {
        return new AppointmentResponse(id, appointmentCode, petId, ownerId, appointmentDate, startTime, endTime, reason,
                bookingChannel, status, cancelReason, confirmedBy, confirmedAt, cancelledBy, cancelledAt, createdAt, updatedAt,
                petName, ownerName, ownerPhone);
    }

    /** Gắn người/lý do/thời điểm duyệt, hủy và lần cập nhật cuối (lấy từ lịch sử trạng thái). */
    public AppointmentResponse withAudit(String cancelReason, UUID confirmedBy, Instant confirmedAt,
                                         UUID cancelledBy, Instant cancelledAt, Instant updatedAt) {
        return new AppointmentResponse(id, appointmentCode, petId, ownerId, appointmentDate, startTime, endTime, reason,
                bookingChannel, status, cancelReason, confirmedBy, confirmedAt, cancelledBy, cancelledAt, createdAt,
                updatedAt != null ? updatedAt : this.updatedAt, petName, ownerName, ownerPhone);
    }
}
