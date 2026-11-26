package com.vetclinic.appointment.service;

import com.vetclinic.appointment.constant.AppointmentStatus;
import com.vetclinic.appointment.constant.BookingChannel;
import com.vetclinic.appointment.dto.response.AppointmentReportResponse;
import com.vetclinic.appointment.dto.response.AppointmentResponse;
import com.vetclinic.appointment.entity.AppointmentStatusHistory;
import com.vetclinic.appointment.repository.AppointmentRepository;
import com.vetclinic.appointment.repository.AppointmentStatusHistoryRepository;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentEnricherAndReportTest {

    /** UUID demo cố định, cùng dạng với dữ liệu seed (019a9442-d810-7000-8000-0000000000NN). */
    private static UUID uid(long n) {
        return UUID.fromString(String.format("019a9442-d810-7000-8000-%012d", n));
    }

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private AppointmentStatusHistoryRepository historyRepository;

    private static UUID aid(long n) {
        return UUID.fromString(String.format("019a9442-d830-7000-8000-%012d", n));
    }

    private static UUID pid(long n) {
        return UUID.fromString(String.format("019a9442-d820-7000-8000-%012d", n));
    }

    /** Response do service trả ra: ownerId + tên thú cưng / chủ nuôi đọc từ cột snapshot; không có cancel_reason / confirmed_* / cancelled_* / updated_at. */
    private static AppointmentResponse appointment(long id, AppointmentStatus status) {
        return new AppointmentResponse(aid(id), "APT-" + id, pid(11), uid(5), LocalDate.of(2026, 10, 31), LocalTime.of(9, 0),
                LocalTime.of(9, 30), "Khám", BookingChannel.ONLINE, status, null, null, null, null, null,
                Instant.parse("2026-10-30T01:00:00Z"), null, "Lu", "Nguyễn Văn A", "0944444441");
    }

    private static AppointmentStatusHistory history(long id, AppointmentStatus from, AppointmentStatus to, UUID by, String notes, String at) {
        return AppointmentStatusHistory.builder().id(id).appointmentId(aid(1)).fromStatus(from).toStatus(to)
                .changedByUserId(by).notes(notes).createdAt(Instant.parse(at)).build();
    }

    // ================================================================== làm giàu từ lịch sử trạng thái

    @Test
    void enrichAll_keepsTheSnapshotsAndNeverCallsAnotherService_oneHistoryQueryForTheWholeList() {
        AppointmentEnricher enricher = new AppointmentEnricher(historyRepository);
        when(historyRepository.findByAppointmentIdInOrderByIdAsc(any())).thenReturn(List.of());

        List<AppointmentResponse> result = enricher.enrichAll(List.of(
                appointment(1, AppointmentStatus.PENDING), appointment(2, AppointmentStatus.PENDING), appointment(3, AppointmentStatus.PENDING)));

        // Tên thú cưng / chủ nuôi nằm sẵn trên appointments (snapshot): không còn N+1 chéo service
        assertThat(result).extracting(AppointmentResponse::petName).containsExactly("Lu", "Lu", "Lu");
        assertThat(result).extracting(AppointmentResponse::ownerName).containsOnly("Nguyễn Văn A");
        assertThat(result.get(0).ownerId()).isEqualTo(uid(5));
        verify(historyRepository).findByAppointmentIdInOrderByIdAsc(any());   // đúng một truy vấn dù 3 lịch hẹn
    }

    @Test
    void enrichAll_whenTheHistoryCannotBeRead_returnsTheAppointmentsUnchanged() {
        when(historyRepository.findByAppointmentIdInOrderByIdAsc(any())).thenThrow(new RuntimeException("db down"));
        AppointmentEnricher enricher = new AppointmentEnricher(historyRepository);

        AppointmentResponse result = enricher.enrich(appointment(1, AppointmentStatus.PENDING));

        assertThat(result.appointmentCode()).isEqualTo("APT-1");
        assertThat(result.petName()).isEqualTo("Lu");
        assertThat(result.confirmedBy()).isNull();
    }

    @Test
    void enrichAll_emptyListMakesNoCalls() {
        AppointmentEnricher enricher = new AppointmentEnricher(historyRepository);

        assertThat(enricher.enrichAll(List.of())).isEmpty();
        verify(historyRepository, never()).findByAppointmentIdInOrderByIdAsc(any());
    }

    @Test
    void enrichAll_derivesConfirmAndCancelAuditFromTheStatusHistory() {
        when(historyRepository.findByAppointmentIdInOrderByIdAsc(any())).thenReturn(List.of(
                history(1, null, AppointmentStatus.PENDING, uid(5), "Chủ nuôi gửi yêu cầu", "2026-10-30T01:00:00Z"),
                history(2, AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED, uid(9), "Lễ tân xác nhận", "2026-10-30T01:10:00Z"),
                history(3, AppointmentStatus.CONFIRMED, AppointmentStatus.CANCELLED, uid(5), "Bận việc đột xuất", "2026-10-30T01:20:00Z")));
        AppointmentEnricher enricher = new AppointmentEnricher(historyRepository);

        AppointmentResponse result = enricher.enrich(appointment(1, AppointmentStatus.CANCELLED));

        assertThat(result.confirmedBy()).isEqualTo(uid(9));
        assertThat(result.confirmedAt()).isEqualTo(Instant.parse("2026-10-30T01:10:00Z"));
        assertThat(result.cancelReason()).isEqualTo("Bận việc đột xuất");
        assertThat(result.cancelledBy()).isEqualTo(uid(5));
        assertThat(result.cancelledAt()).isEqualTo(Instant.parse("2026-10-30T01:20:00Z"));
        assertThat(result.updatedAt()).isEqualTo(Instant.parse("2026-10-30T01:20:00Z"));   // lần đổi cuối cùng
    }

    @Test
    void enrichAll_receptionistCreatedAppointment_countsTheCreationAsTheConfirmation() {
        when(historyRepository.findByAppointmentIdInOrderByIdAsc(any())).thenReturn(List.of(
                history(1, null, AppointmentStatus.CONFIRMED, uid(7), "Lễ tân tạo lịch hẹn", "2026-10-30T01:00:00Z")));
        AppointmentEnricher enricher = new AppointmentEnricher(historyRepository);

        AppointmentResponse result = enricher.enrich(appointment(1, AppointmentStatus.CONFIRMED));

        assertThat(result.confirmedBy()).isEqualTo(uid(7));
        assertThat(result.cancelReason()).isNull();
        assertThat(result.cancelledBy()).isNull();
    }

    // ================================================================== báo cáo

    @Test
    void report_computesNoShowAndCancellationRates() {
        AppointmentReportService report = new AppointmentReportService(appointmentRepository, CLOCK);
        when(appointmentRepository.countByStatusBetween(any(), any())).thenReturn(List.of(
                new Object[]{AppointmentStatus.CHECKED_IN, 8L}, new Object[]{AppointmentStatus.NO_SHOW, 2L},
                new Object[]{AppointmentStatus.CANCELLED, 5L}, new Object[]{AppointmentStatus.PENDING, 5L}));
        when(appointmentRepository.countByChannelBetween(any(), any())).thenReturn(List.of(
                new Object[]{BookingChannel.ONLINE, 12L}, new Object[]{BookingChannel.PHONE, 8L}));

        AppointmentReportResponse result = report.summary(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 30));

        assertThat(result.totalAppointments()).isEqualTo(20);
        assertThat(result.noShowRate()).isEqualTo(0.2);          // 2 / (8 + 2)
        assertThat(result.cancellationRate()).isEqualTo(0.25);   // 5 / 20
        assertThat(result.byStatus()).containsEntry("CONFIRMED", 0L).containsEntry("NO_SHOW", 2L);   // đủ mọi trạng thái
        assertThat(result.byChannel()).containsEntry("ONLINE", 12L).containsEntry("PHONE", 8L).containsEntry("WALK_IN", 0L);
    }

    @Test
    void report_noOutcomesYetMeansZeroRatesNotDivisionByZero() {
        AppointmentReportService report = new AppointmentReportService(appointmentRepository, CLOCK);
        when(appointmentRepository.countByStatusBetween(any(), any())).thenReturn(List.of());
        when(appointmentRepository.countByChannelBetween(any(), any())).thenReturn(List.of());

        AppointmentReportResponse result = report.summary(null, null);

        assertThat(result.noShowRate()).isZero();
        assertThat(result.cancellationRate()).isZero();
        assertThat(result.toDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(result.fromDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void report_refusesInvertedOrTooLongRanges() {
        AppointmentReportService report = new AppointmentReportService(appointmentRepository, CLOCK);

        assertThatThrownBy(() -> report.summary(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA));
        assertThatThrownBy(() -> report.summary(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 30)))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA));
    }
}
