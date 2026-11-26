package com.vetclinic.appointment.scheduler;

import com.vetclinic.appointment.config.BookingProperties;
import com.vetclinic.appointment.constant.AppointmentStatus;
import com.vetclinic.appointment.repository.AppointmentRepository;
import com.vetclinic.appointment.service.AppointmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentNoShowSchedulerTest {

    // 2026-10-30 09:00 giờ Việt Nam. Hạn vắng mặt = giờ bắt đầu + 30 phút => lịch bắt đầu TRƯỚC 08:30 là quá hạn
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    private static UUID id(long n) {
        return UUID.fromString(String.format("019a9442-d830-7000-8000-%012d", n));
    }

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private AppointmentService appointmentService;

    private AppointmentNoShowScheduler scheduler() {
        BookingProperties properties = new BookingProperties("Asia/Ho_Chi_Minh", 30,
                List.of("08:00", "08:30"), 30, 2, 60, 5);
        return new AppointmentNoShowScheduler(appointmentRepository, appointmentService, properties, CLOCK);
    }

    @Test
    void scan_queriesConfirmedAppointmentsStartedBeforeCutoff() {
        when(appointmentRepository.findIdsStartedBefore(AppointmentStatus.CONFIRMED,
                LocalDate.of(2026, 10, 30), LocalTime.of(8, 30))).thenReturn(List.of());

        scheduler().scanAndMarkNoShowAppointments();

        verify(appointmentService, never()).markNoShowBySystem(ArgumentMatchers.any(UUID.class));
    }

    @Test
    void scan_oneFailingAppointmentDoesNotStopTheRest() {
        when(appointmentRepository.findIdsStartedBefore(AppointmentStatus.CONFIRMED,
                LocalDate.of(2026, 10, 30), LocalTime.of(8, 30))).thenReturn(List.of(id(1), id(2), id(3)));
        when(appointmentService.markNoShowBySystem(id(1))).thenReturn(true);
        when(appointmentService.markNoShowBySystem(id(2))).thenThrow(new IllegalStateException("lỗi giả lập"));
        when(appointmentService.markNoShowBySystem(id(3))).thenReturn(true);

        scheduler().scanAndMarkNoShowAppointments();

        verify(appointmentService).markNoShowBySystem(id(1));
        verify(appointmentService).markNoShowBySystem(id(2));
        verify(appointmentService).markNoShowBySystem(id(3));
    }
}
