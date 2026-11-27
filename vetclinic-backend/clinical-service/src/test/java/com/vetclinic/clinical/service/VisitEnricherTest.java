package com.vetclinic.clinical.service;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.security.RoleCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.List;

import static com.vetclinic.clinical.testsupport.TestData.lid;
import static com.vetclinic.clinical.testsupport.TestData.uid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** pet_visits v3.0 không còn assigned_room: số phòng = users.room_number của Bác sĩ, gắn bằng MỘT lời gọi theo lô. */
@ExtendWith(MockitoExtension.class)
class VisitEnricherTest {

    @Mock
    private AuthClient authClient;

    private static VisitResponse visit(long id, UUID doctorId) {
        return new VisitResponse(com.vetclinic.clinical.testsupport.TestData.lid(id), "VIS-" + id, uid(11), "Lu", "PET-11", "Chó", uid(5), null,
                doctorId, null, null, uid(9), null, false, VisitStatus.WAITING, false, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static UserSummaryResponse doctor(long id, String room) {
        return new UserSummaryResponse(uid(id), "BS " + id, "0911111111", RoleCode.VETERINARIAN, List.of(RoleCode.VETERINARIAN), room, "ACTIVE");
    }

    @Test
    void enrichAll_attachesTheDoctorsRoomWithOneBatchCall() {
        when(authClient.getUsers(any())).thenReturn(ApiResponse.success(List.of(doctor(2L, "P101"), doctor(3L, "P102"))));

        List<VisitResponse> result = new VisitEnricher(authClient)
                .enrichAll(List.of(visit(1, uid(2)), visit(2, uid(3)), visit(3, uid(2)), visit(4, null)));

        assertThat(result).extracting(VisitResponse::assignedRoom).containsExactly("P101", "P102", "P101", null);
        verify(authClient).getUsers(any());   // đúng một lời gọi dù 4 ca khám: không N+1
    }

    @Test
    void enrichAll_marksADoctorWhoWasLockedAsNotActive_EX17_whileKeepingTheSnapshotName() {
        UserSummaryResponse locked = new UserSummaryResponse(uid(3), "BS 3", "0911111111", RoleCode.VETERINARIAN, List.of(RoleCode.VETERINARIAN), "P102", "LOCKED");
        when(authClient.getUsers(any())).thenReturn(ApiResponse.success(List.of(doctor(2L, "P101"), locked)));

        List<VisitResponse> result = new VisitEnricher(authClient).enrichAll(List.of(visit(1, uid(2)), visit(2, uid(3)), visit(3, null)));

        assertThat(result).extracting(VisitResponse::doctorActive).containsExactly(true, false, null);   // ca chưa có Bác sĩ: không biết
        assertThat(result.get(1).assignedRoom()).isEqualTo("P102");
    }

    @Test
    void enrich_whenAuthServiceIsDown_doesNotClaimTheDoctorIsLocked() {
        when(authClient.getUsers(any())).thenThrow(new RuntimeException("auth down"));

        assertThat(new VisitEnricher(authClient).enrich(visit(1, uid(2))).doctorActive()).isNull();   // null = chưa biết, không gắn nhãn "Đã khóa"
    }

    @Test
    void enrichAll_whenNoVisitHasADoctor_makesNoCall() {
        List<VisitResponse> result = new VisitEnricher(authClient).enrichAll(List.of(visit(1, null)));

        assertThat(result).hasSize(1);
        verify(authClient, never()).getUsers(any());
    }

    @Test
    void enrich_whenAuthServiceIsDown_returnsTheVisitUnchanged() {
        when(authClient.getUsers(any())).thenThrow(new RuntimeException("auth down"));

        VisitResponse result = new VisitEnricher(authClient).enrich(visit(1, uid(2)));

        assertThat(result.assignedRoom()).isNull();
        assertThat(result.id()).isEqualTo(com.vetclinic.clinical.testsupport.TestData.lid(1L));
    }
}
