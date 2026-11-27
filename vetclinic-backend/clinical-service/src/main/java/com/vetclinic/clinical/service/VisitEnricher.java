package com.vetclinic.clinical.service;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gắn số phòng và trạng thái hoạt động (EX-17) của Bác sĩ phụ trách vào VisitResponse. pet_visits v3.0 không còn cột assigned_room: phòng suy ra từ
 * {@code users.room_number} của Bác sĩ (auth-service) bằng MỘT lời gọi theo lô. Gọi ở tầng controller, NGOÀI transaction
 * (quy chuẩn §9.1). Chỉ là phần hiển thị: auth-service tạm sập thì để null, không làm hỏng response.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitEnricher {

    private final AuthClient authClient;

    public VisitResponse enrich(VisitResponse visit) {
        return enrichAll(List.of(visit)).get(0);
    }

    public List<VisitResponse> enrichAll(List<VisitResponse> visits) {
        Set<UUID> doctorIds = visits.stream().map(VisitResponse::doctorId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (doctorIds.isEmpty()) {
            return visits;
        }
        Map<UUID, UserSummaryResponse> doctors = doctorsOf(doctorIds);
        return visits.stream().map(v -> {
            if (v.doctorId() == null) {
                return v;
            }
            UserSummaryResponse doctor = doctors.get(v.doctorId());
            // EX-17: Bác sĩ bị khóa / ngừng -> doctorActive = false; không tra được (auth-service sập) -> null, giao diện không gắn nhãn
            return v.withDoctorInfo(doctor == null ? null : doctor.roomNumber(), doctor == null ? null : "ACTIVE".equals(doctor.status()));
        }).toList();
    }

    private Map<UUID, UserSummaryResponse> doctorsOf(Set<UUID> doctorIds) {
        try {
            ApiResponse<List<UserSummaryResponse>> response = authClient.getUsers(List.copyOf(doctorIds));
            Map<UUID, UserSummaryResponse> doctors = new HashMap<>();
            if (response != null && response.getData() != null) {
                for (UserSummaryResponse u : response.getData()) {
                    doctors.put(u.id(), u);
                }
            }
            return doctors;
        } catch (RuntimeException e) {
            log.warn("Không lấy được thông tin Bác sĩ từ auth-service: {}", e.getMessage());
            return Map.of();
        }
    }
}
