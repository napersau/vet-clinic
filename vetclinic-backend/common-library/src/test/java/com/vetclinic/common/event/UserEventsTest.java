package com.vetclinic.common.event;

import com.vetclinic.common.util.UuidV7;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserEventsTest {

    private static final Instant NOW = Instant.parse("2026-11-21T03:00:00Z");
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void userStatusChangedEvent_hasItsRoutingKey_andSurvivesAJsonRoundTrip() {
        var event = new UserStatusChangedEvent(UuidV7.generate(), NOW, UuidV7.generate(), UuidV7.generate(),
                "BS Mai", "ACTIVE", "LOCKED", List.of("VETERINARIAN", "ADMIN"));

        assertThat(event.routingKey()).isEqualTo("user.status_changed").isEqualTo(EventRouting.USER_STATUS_CHANGED);
        assertThat(mapper.readValue(mapper.writeValueAsString(event), UserStatusChangedEvent.class)).isEqualTo(event);
    }

    @Test
    void userProfileUpdatedEvent_hasItsRoutingKey_andSurvivesAJsonRoundTrip() {
        var event = new UserProfileUpdatedEvent(UuidV7.generate(), NOW, UuidV7.generate(), UuidV7.generate(),
                "Lê Thị Mai", "0902000004", null, "P103");

        assertThat(event.routingKey()).isEqualTo("user.profile_updated").isEqualTo(EventRouting.USER_PROFILE_UPDATED);
        assertThat(mapper.readValue(mapper.writeValueAsString(event), UserProfileUpdatedEvent.class)).isEqualTo(event);
    }
}
