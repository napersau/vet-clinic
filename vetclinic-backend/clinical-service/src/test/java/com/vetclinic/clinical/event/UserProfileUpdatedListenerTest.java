package com.vetclinic.clinical.event;

import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.service.PetService;
import com.vetclinic.common.event.UserProfileUpdatedEvent;
import com.vetclinic.common.event.processed.IdempotentConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static com.vetclinic.clinical.testsupport.TestData.uid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileUpdatedListenerTest {

    @Mock
    private PetService petService;
    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private IdempotentConsumer idempotentConsumer;

    private UserProfileUpdatedListener listener;

    @BeforeEach
    void setUp() {
        // IdempotentConsumer thật cần processed_events: ở đây chỉ chạy thẳng hành động
        lenient().when(idempotentConsumer.run(any(), any(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(2)).run();
            return true;
        });
        listener = new UserProfileUpdatedListener(petService, visitRepository, idempotentConsumer);
    }

    @Test
    void profileUpdated_refreshesTheOwnerSnapshotOnPets_andTheDoctorNameSnapshotOnVisits() {
        UUID user = uid(21);
        UserProfileUpdatedEvent event = new UserProfileUpdatedEvent(UUID.randomUUID(), Instant.now(), user, user,
                "BS Nguyễn Văn Tiến", "0912000002", null, "P101");
        when(petService.refreshOwnerSnapshot(user, "BS Nguyễn Văn Tiến", "0912000002")).thenReturn(0);

        listener.onUserProfileUpdated(event);

        verify(petService).refreshOwnerSnapshot(user, "BS Nguyễn Văn Tiến", "0912000002");
        verify(visitRepository).updateDoctorNameSnapshot(eq(user), eq("BS Nguyễn Văn Tiến"));
    }

    @Test
    void profileUpdatedWithoutAName_doesNotTouchTheVisits() {
        UUID user = uid(21);

        listener.onUserProfileUpdated(new UserProfileUpdatedEvent(UUID.randomUUID(), Instant.now(), user, user, null, "0912000002", null, null));

        verify(visitRepository, never()).updateDoctorNameSnapshot(any(), any());
    }
}
