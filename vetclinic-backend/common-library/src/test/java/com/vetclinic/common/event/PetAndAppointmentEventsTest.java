package com.vetclinic.common.event;

import com.vetclinic.common.util.UuidV7;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** v4.0 (bước 4.1): id thú cưng và lịch hẹn trong sự kiện là UUID. */
class PetAndAppointmentEventsTest {

    private static final Instant NOW = Instant.parse("2026-11-21T03:00:00Z");
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void petUpdatedEvent_hasItsRoutingKey_andSurvivesAJsonRoundTrip() {
        var event = new PetUpdatedEvent(UuidV7.generate(), NOW, UuidV7.generate(), UuidV7.generate(), "Lu", UuidV7.generate(),
                "Hoàng Thị Lan", "0902000008");

        assertThat(event.routingKey()).isEqualTo("pet.updated").isEqualTo(EventRouting.PET_UPDATED);
        assertThat(mapper.readValue(mapper.writeValueAsString(event), PetUpdatedEvent.class)).isEqualTo(event);
    }

    @Test
    void appointmentBookedEvent_carriesUuidIds() {
        var event = new AppointmentBookedEvent(UuidV7.generate(), NOW, UuidV7.generate(), UuidV7.generate(), "APT-20261121-K3F9QX",
                UuidV7.generate(), UuidV7.generate(), LocalDate.of(2026, 11, 22), LocalTime.of(9, 0), "PENDING");

        assertThat(mapper.readValue(mapper.writeValueAsString(event), AppointmentBookedEvent.class)).isEqualTo(event);
    }

    @Test
    void visitCheckedInEvent_walkInHasNoAppointmentId() {
        var event = new VisitCheckedInEvent(UuidV7.generate(), NOW, UuidV7.generate(), UuidV7.generate(), "VIS-2026-00007", UuidV7.generate(), null, false, null);

        assertThat(mapper.readValue(mapper.writeValueAsString(event), VisitCheckedInEvent.class)).isEqualTo(event);
        assertThat(event.appointmentId()).isNull();
    }
}
