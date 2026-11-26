package com.vetclinic.appointment.event;

import com.vetclinic.appointment.service.AppointmentService;
import com.vetclinic.common.event.VisitCheckedInEvent;
import com.vetclinic.common.event.processed.IdempotentConsumer;
import com.vetclinic.common.event.processed.ProcessedEvent;
import com.vetclinic.common.event.processed.ProcessedEventId;
import com.vetclinic.common.event.processed.ProcessedEventRepository;
import com.vetclinic.common.util.UuidV7;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VisitCheckedInListenerTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final UUID ACTOR = UUID.fromString("019a9442-d810-7000-8000-000000000005");

    private final AppointmentService appointmentService = mock(AppointmentService.class);
    private final ProcessedEventRepository processedRepository = mock(ProcessedEventRepository.class);
    private final VisitCheckedInListener listener = new VisitCheckedInListener(appointmentService,
            new IdempotentConsumer(processedRepository, Clock.fixed(NOW, ZoneOffset.UTC)));

    private static final UUID APPT = UUID.fromString("019a9442-d830-7000-8000-000000000009");
    private static final UUID PET = UUID.fromString("019a9442-d820-7000-8000-000000000001");

    private static VisitCheckedInEvent event(UUID eventId, UUID appointmentId) {
        return new VisitCheckedInEvent(eventId, NOW, ACTOR, UuidV7.generate(), "VIS-2026-00042", PET, appointmentId, false, UuidV7.generate());
    }

    @Test
    void aNewEventMarksTheAppointmentCheckedIn_andIsRecordedForThisListener() {
        UUID eventId = UuidV7.generate();

        listener.onVisitCheckedIn(event(eventId, APPT));

        verify(appointmentService).markCheckedIn(APPT, ACTOR, "VIS-2026-00042");
        verify(processedRepository).saveAndFlush(any(ProcessedEvent.class));
    }

    @Test
    void aDuplicateDeliveryOfTheSameEventDoesNothing() {
        UUID eventId = UuidV7.generate();
        when(processedRepository.existsById(new ProcessedEventId(eventId, VisitCheckedInListener.CONSUMER))).thenReturn(true);

        listener.onVisitCheckedIn(event(eventId, APPT));

        verify(appointmentService, never()).markCheckedIn(any(), any(), anyString());
        verify(processedRepository, never()).saveAndFlush(any());
    }

    @Test
    void aWalkInVisitHasNoAppointmentToUpdate_andRecordsNothing() {
        listener.onVisitCheckedIn(event(UuidV7.generate(), null));

        verify(appointmentService, never()).markCheckedIn(any(), any(), anyString());
        verify(processedRepository, never()).saveAndFlush(any());
    }

    @Test
    void aFailingTransitionIsNotRecorded_soRabbitMqRetryRunsItAgain() {
        UUID eventId = UuidV7.generate();
        doThrow(new IllegalStateException("db down")).when(appointmentService).markCheckedIn(APPT, ACTOR, "VIS-2026-00042");

        try {
            listener.onVisitCheckedIn(event(eventId, APPT));
        } catch (IllegalStateException expected) {
            // RabbitMQ sẽ thử lại
        }

        verify(processedRepository, never()).saveAndFlush(any());
        verify(appointmentService, times(1)).markCheckedIn(APPT, ACTOR, "VIS-2026-00042");
    }
}
