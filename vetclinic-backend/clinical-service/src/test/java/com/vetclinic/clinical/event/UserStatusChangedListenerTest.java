package com.vetclinic.clinical.event;

import com.vetclinic.clinical.service.DoctorAvailabilityService;
import com.vetclinic.common.event.UserStatusChangedEvent;
import com.vetclinic.common.event.processed.IdempotentConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.vetclinic.clinical.testsupport.TestData.uid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserStatusChangedListenerTest {

    @Mock
    private DoctorAvailabilityService availability;
    @Mock
    private IdempotentConsumer idempotentConsumer;

    private UserStatusChangedListener listener;

    @BeforeEach
    void setUp() {
        // IdempotentConsumer thật cần processed_events: ở đây chỉ chạy thẳng hành động
        lenient().when(idempotentConsumer.run(any(), any(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(2)).run();
            return true;
        });
        listener = new UserStatusChangedListener(availability, idempotentConsumer);
    }

    private static UserStatusChangedEvent event(String to, List<String> roles) {
        return new UserStatusChangedEvent(UUID.randomUUID(), Instant.now(), uid(1), uid(21), "BS Tiến", "ACTIVE", to, roles);
    }

    @Test
    void aLockedOrDeactivatedDoctor_hasItsWaitingVisitsReleased_idempotently() {
        UserStatusChangedEvent locked = event("LOCKED", List.of("VETERINARIAN"));
        listener.onUserStatusChanged(locked);
        verify(idempotentConsumer).run(eq(locked.eventId()), eq(UserStatusChangedListener.CONSUMER), any());
        verify(availability).releaseWaitingVisitsOf(uid(21));

        listener.onUserStatusChanged(event("INACTIVE", List.of("VETERINARIAN", "ADMIN")));
        verify(availability, org.mockito.Mockito.times(2)).releaseWaitingVisitsOf(uid(21));
    }

    @Test
    void reactivation_orAnAccountThatIsNotADoctor_doesNothing() {
        listener.onUserStatusChanged(event("ACTIVE", List.of("VETERINARIAN")));
        listener.onUserStatusChanged(event("LOCKED", List.of("RECEPTIONIST")));
        listener.onUserStatusChanged(event("LOCKED", null));

        verifyNoInteractions(availability);
        verify(idempotentConsumer, never()).run(any(), any(), any());
    }
}
