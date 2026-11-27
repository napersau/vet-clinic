package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.DoctorStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.DoctorStatusResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.queue.QueueRedisRepository;
import com.vetclinic.clinical.constant.QueueType;
import org.junit.jupiter.api.BeforeEach;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static com.vetclinic.clinical.testsupport.TestData.uid;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoctorStatusServiceImplTest {

    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private QueueRedisRepository redis;
    @Mock
    private AuthClient authClient;
    private DoctorStatusServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DoctorStatusServiceImpl(visitRepository, redis, authClient, com.vetclinic.clinical.testsupport.TestData.CLOCK);
    }

    @Test
    void statusOf_isBusyWhenExaminingAPatient() {
        when(visitRepository.existsByDoctorIdAndStatus(uid(1), VisitStatus.IN_PROGRESS)).thenReturn(true);

        assertThat(service.statusOf(uid(1))).isEqualTo(DoctorStatus.BUSY);
    }

    @Test
    void statusOf_isNotBusyJustBecauseAPatientWasAssigned_FR22() {
        // v3.0: ca WAITING đã gán KHÔNG làm Bác sĩ BUSY (chỉ "có ca IN_PROGRESS" mới BUSY); Lễ tân vẫn xếp thêm bất cứ lúc nào
        when(visitRepository.existsByDoctorIdAndStatus(uid(1), VisitStatus.IN_PROGRESS)).thenReturn(false);

        assertThat(service.statusOf(uid(1))).isEqualTo(DoctorStatus.AVAILABLE);
        verify(visitRepository, never()).existsByDoctorIdAndStatus(uid(1), VisitStatus.WAITING);
    }

    @Test
    void statusOf_isAvailableWhenNothingIsPending_evenIfResultsAreWaiting() {
        // v3.0 không còn "Khóa ưu tiên": ca RESULTS_READY nằm đầu hàng đợi của bác sĩ nhưng không làm đổi trạng thái bác sĩ
        assertThat(service.statusOf(uid(1))).isEqualTo(DoctorStatus.AVAILABLE);
    }

    @Test
    void doctorStatus_hasOnlyAvailableAndBusy() {
        assertThat(DoctorStatus.values()).containsExactly(DoctorStatus.AVAILABLE, DoctorStatus.BUSY);
    }

    @Test
    void statusOf_isBusyWhileExaminingAPatient() {
        when(visitRepository.existsByDoctorIdAndStatus(uid(1), VisitStatus.IN_PROGRESS)).thenReturn(true);

        assertThat(service.statusOf(uid(1))).isEqualTo(DoctorStatus.BUSY);
    }

    @Test
    void board_combinesActiveDoctorsFromAuthWithTheirComputedStatus() {
        when(authClient.getActiveDoctors()).thenReturn(ApiResponse.success(List.of(
                doctor(1L, "BS A", "P01"), doctor(2L, "BS B", "P02"), doctor(3L, "BS C", "P03"))));
        when(visitRepository.findDoctorIdsWithStatusIn(List.of(VisitStatus.IN_PROGRESS))).thenReturn(Set.of(uid(1)));
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 30);   // CLOCK cố định 30/10/2026
        when(redis.waitingCount(today, QueueType.DOCTOR, uid(1))).thenReturn(2L);   // ZCARD số phiếu DOCTOR đang chờ
        when(redis.waitingCount(today, QueueType.DOCTOR, uid(2))).thenReturn(3L);
        when(redis.waitingCount(today, QueueType.DOCTOR, uid(3))).thenReturn(0L);

        List<DoctorStatusResponse> board = service.board();

        assertThat(board).extracting(DoctorStatusResponse::status)
                .containsExactly(DoctorStatus.BUSY, DoctorStatus.AVAILABLE, DoctorStatus.AVAILABLE);
        assertThat(board).extracting(DoctorStatusResponse::roomNumber).containsExactly("P01", "P02", "P03");
        assertThat(board).extracting(DoctorStatusResponse::waitingCount).containsExactly(2, 3, 0);
    }

    @Test
    void board_failsAsServiceUnavailableWhenAuthReturnsNothing() {
        when(authClient.getActiveDoctors()).thenReturn(null);

        assertError(ErrorCode.SERVICE_UNAVAILABLE, () -> service.board());
    }

    private static UserSummaryResponse doctor(long id, String name, String room) {
        return new UserSummaryResponse(uid(id), name, "0911111111", RoleCode.VETERINARIAN, List.of(RoleCode.VETERINARIAN), room, "ACTIVE");
    }
}
