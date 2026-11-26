package com.vetclinic.appointment.service;

import com.vetclinic.appointment.constant.AppointmentStatus;
import com.vetclinic.appointment.dto.response.AppointmentResponse;
import com.vetclinic.appointment.entity.AppointmentStatusHistory;
import com.vetclinic.appointment.repository.AppointmentStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Hoàn thiện response của lịch hẹn bằng thông tin suy ra từ {@code appointment_status_histories} (DB của chính service này): người / lý do /
 * thời điểm duyệt và hủy, lần cập nhật cuối (appointments không có cột confirmed_*, cancelled_*, cancel_reason, updated_at), bằng MỘT câu
 * truy vấn theo lô cho cả danh sách (không N+1).
 * <p>Từ v4.0 không còn gọi clinical-service / auth-service để lấy tên thú cưng, chủ nuôi: các giá trị đó nằm sẵn trên appointments dưới dạng
 * snapshot, nên danh sách lịch hẹn không phụ thuộc service khác và không bị lỗi N+1 chéo service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppointmentEnricher {

    private final AppointmentStatusHistoryRepository historyRepository;

    public AppointmentResponse enrich(AppointmentResponse appointment) {
        return enrichAll(List.of(appointment)).get(0);
    }

    public List<AppointmentResponse> enrichAll(List<AppointmentResponse> appointments) {
        if (appointments.isEmpty()) {
            return appointments;
        }
        Map<UUID, List<AppointmentStatusHistory>> histories = histories(
                appointments.stream().map(AppointmentResponse::id).collect(Collectors.toSet()));
        return appointments.stream().map(a -> withAudit(a, histories.getOrDefault(a.id(), List.of()))).toList();
    }

    /**
     * Suy ra các trường kiểm toán từ lịch sử (đã sắp theo id tăng dần): lần CONFIRMED đầu tiên là lần duyệt (hoặc lễ tân tạo
     * lịch), lần CANCELLED cuối là lần hủy; lý do hủy nằm ở {@code notes}; {@code changedByUserId = null} là hệ thống.
     */
    private static AppointmentResponse withAudit(AppointmentResponse response, List<AppointmentStatusHistory> history) {
        if (history.isEmpty()) {
            return response;
        }
        AppointmentStatusHistory confirmed = history.stream()
                .filter(h -> h.getToStatus() == AppointmentStatus.CONFIRMED && h.getFromStatus() != AppointmentStatus.CONFIRMED)
                .findFirst().orElse(null);
        AppointmentStatusHistory cancelled = null;
        for (AppointmentStatusHistory h : history) {
            if (h.getToStatus() == AppointmentStatus.CANCELLED && h.getFromStatus() != AppointmentStatus.CANCELLED) {
                cancelled = h;
            }
        }
        return response.withAudit(
                cancelled == null ? null : cancelled.getNotes(),
                confirmed == null ? null : confirmed.getChangedByUserId(),
                confirmed == null ? null : confirmed.getCreatedAt(),
                cancelled == null ? null : cancelled.getChangedByUserId(),
                cancelled == null ? null : cancelled.getCreatedAt(),
                history.get(history.size() - 1).getCreatedAt());
    }

    private Map<UUID, List<AppointmentStatusHistory>> histories(Collection<UUID> appointmentIds) {
        try {
            return historyRepository.findByAppointmentIdInOrderByIdAsc(appointmentIds).stream()
                    .collect(Collectors.groupingBy(AppointmentStatusHistory::getAppointmentId));
        } catch (RuntimeException e) {
            log.warn("Không đọc được lịch sử trạng thái của lịch hẹn: {}", e.getMessage());
            return Map.of();
        }
    }
}
