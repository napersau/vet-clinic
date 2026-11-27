package com.vetclinic.clinical.service;

import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.repository.PetVisitRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.lid;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** EX-17: Bác sĩ bị khóa thì các ca đang chờ được gỡ phân công, phiếu về chờ phân công (giữ số và giờ phát). */
@ExtendWith(MockitoExtension.class)
class DoctorAvailabilityServiceTest {

    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private com.vetclinic.clinical.queue.QueueCoordinator ticketIssuer;

    @Test
    void releaseWaitingVisitsOf_unassignsEveryWaitingVisitAndMovesItsTicketToTheUnassignedList() {
        PetVisit a = visit(10L, VisitStatus.WAITING, DOCTOR);
        a.setDoctorNameSnapshot("BS Tiến");
        PetVisit b = visit(11L, VisitStatus.WAITING, DOCTOR);
        when(visitRepository.findByDoctorIdAndStatusForUpdate(DOCTOR, VisitStatus.WAITING)).thenReturn(List.of(a, b));

        int released = new DoctorAvailabilityService(visitRepository, ticketIssuer).releaseWaitingVisitsOf(DOCTOR);

        assertThat(released).isEqualTo(2);
        assertThat(List.of(a, b)).allSatisfy(v -> {
            assertThat(v.getDoctorId()).isNull();
            assertThat(v.getDoctorNameSnapshot()).isNull();
            assertThat(v.getStatus()).isEqualTo(VisitStatus.WAITING);   // trạng thái không đổi (Q2: không ghi lịch sử trạng thái)
        });
        InOrder order = Mockito.inOrder(ticketIssuer);
        order.verify(ticketIssuer).reassignDoctorTicket(lid(10L), null);   // reassign GIỮ số phiếu + giờ phát
        order.verify(ticketIssuer).reassignDoctorTicket(lid(11L), null);
        verify(visitRepository, Mockito.times(2)).save(any(PetVisit.class));
    }

    @Test
    void releaseWaitingVisitsOf_aDoctorWithoutWaitingVisits_changesNothing() {
        when(visitRepository.findByDoctorIdAndStatusForUpdate(DOCTOR, VisitStatus.WAITING)).thenReturn(List.of());

        assertThat(new DoctorAvailabilityService(visitRepository, ticketIssuer).releaseWaitingVisitsOf(DOCTOR)).isZero();

        verify(visitRepository, never()).save(any());
        verifyNoInteractions(ticketIssuer);
    }
}
