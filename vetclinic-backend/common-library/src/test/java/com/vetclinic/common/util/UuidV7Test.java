package com.vetclinic.common.util;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UuidV7Test {

    @Test
    void hasVersion7_rfcVariant_andCarriesTheTimestamp() {
        Instant at = Instant.parse("2026-11-20T03:00:00Z");
        UUID id = UuidV7.generate(Clock.fixed(at, ZoneOffset.UTC));

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);   // 10xx (RFC 9562)
        assertThat(UuidV7.timestampOf(id)).isEqualTo(at.toEpochMilli());
    }

    @Test
    void laterMillisecondsAlwaysSortAfter_soInsertsAppendToTheIndex() {
        List<UUID> ids = new ArrayList<>();
        for (long ms = 1_763_600_000_000L; ms < 1_763_600_000_050L; ms++) {
            ids.add(UuidV7.generate(ms));
        }
        List<String> asText = ids.stream().map(UUID::toString).toList();   // BINARY(16) so sánh theo byte = thứ tự chuỗi hex
        assertThat(asText).isSorted();
    }

    @Test
    void manyIdsInTheSameMillisecondAreStillUnique() {
        HashSet<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(UuidV7.generate(1_763_600_000_000L));
        }
        assertThat(ids).hasSize(10_000);
    }

    @Test
    void timestampOfRejectsOtherVersions() {
        assertThatThrownBy(() -> UuidV7.timestampOf(UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }
}
