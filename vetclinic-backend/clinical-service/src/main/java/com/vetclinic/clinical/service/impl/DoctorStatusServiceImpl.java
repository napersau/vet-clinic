package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.DoctorStatus;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.DoctorStatusResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.queue.QueueRedisRepository;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.service.DoctorStatusService;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Không @Transactional: board() gọi OpenFeign, các truy vấn đọc tự chạy trong transaction riêng của repository; {@code waitingCount} = ZCARD trên Redis. */
@Service
@RequiredArgsConstructor
public class DoctorStatusServiceImpl implements DoctorStatusService {

    private final PetVisitRepository visitRepository;
    private final QueueRedisRepository redis;
    private final AuthClient authClient;
    private final Clock clock;

    @Override
    public DoctorStatus statusOf(UUID doctorId) {
        return visitRepository.existsByDoctorIdAndStatus(doctorId, VisitStatus.IN_PROGRESS)
                ? DoctorStatus.BUSY : DoctorStatus.AVAILABLE;
    }

    @Override
    public List<DoctorStatusResponse> board() {
        ApiResponse<List<UserSummaryResponse>> response = authClient.getActiveDoctors();
        if (response == null || response.getData() == null) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        Set<UUID> busy = visitRepository.findDoctorIdsWithStatusIn(List.of(VisitStatus.IN_PROGRESS));
        LocalDate today = LocalDate.now(clock);

        return response.getData().stream().map(d -> {
            DoctorStatus status = busy.contains(d.id()) ? DoctorStatus.BUSY : DoctorStatus.AVAILABLE;
            // số phiếu DOCTOR đang chờ của Bác sĩ (ca chưa nộp phí khám không có phiếu nên không tính)
            int waiting = (int) redis.waitingCount(today, QueueType.DOCTOR, d.id());
            return new DoctorStatusResponse(d.id(), d.fullName(), d.roomNumber(), status, waiting);
        }).toList();
    }
}
