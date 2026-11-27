package com.vetclinic.clinical.queue;

import com.vetclinic.clinical.constant.QueueType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueueKeysTest {

    private static final UUID DOCTOR = UUID.fromString("019a9442-d810-7000-8000-000000000002");
    private static final UUID VISIT = UUID.fromString("019a9442-d840-7000-8000-000000000100");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);

    @Test
    void keysFollowTheDocumentedLayout() {
        assertThat(QueueKeys.queue(DAY, QueueType.DOCTOR, DOCTOR)).isEqualTo("queue:20261121:DOCTOR:" + DOCTOR);
        assertThat(QueueKeys.queue(DAY, QueueType.DOCTOR, null)).isEqualTo("queue:20261121:DOCTOR:UNASSIGNED");
        assertThat(QueueKeys.queue(DAY, QueueType.LABORATORY, null)).isEqualTo("queue:20261121:LABORATORY:ALL");
        assertThat(QueueKeys.queue(DAY, QueueType.IMAGING, DOCTOR)).isEqualTo("queue:20261121:IMAGING:ALL");   // phòng XN / CĐHA bỏ qua doctorId
        assertThat(QueueKeys.seq(DAY, QueueType.IMAGING)).isEqualTo("queue:seq:20261121:IMAGING");
        assertThat(QueueKeys.dedupe("evt:DOCTOR")).isEqualTo("queue:dedupe:evt:DOCTOR");
        assertThat(QueueKeys.visit(VISIT)).isEqualTo("queue:visit:" + VISIT);
        assertThat(QueueKeys.ticket(VISIT)).isEqualTo("ticket:" + VISIT);
        assertThat(QueueKeys.busy(DOCTOR)).isEqualTo("queue:busy:" + DOCTOR);
        assertThat(QueueKeys.meta(DAY)).isEqualTo("queue:meta:20261121");
    }

    @Test
    void scoreMakesPriorityBeatArrivalTimeAndKeepsFifoWithinAPriority() {
        long early = Instant.parse("2026-11-21T01:00:00Z").toEpochMilli();
        long late = Instant.parse("2026-11-21T09:00:00Z").toEpochMilli();
        assertThat(QueueKeys.score(0, early)).isEqualTo(2 * 10_000_000_000_000L + early);
        assertThat(QueueKeys.score(2, late)).isLessThan(QueueKeys.score(1, early));     // cấp cứu đến muộn vẫn trước ca có kết quả
        assertThat(QueueKeys.score(1, late)).isLessThan(QueueKeys.score(0, early));     // ca có kết quả đến muộn vẫn trước ca thường đến sớm
        assertThat(QueueKeys.score(0, early)).isLessThan(QueueKeys.score(0, late));     // cùng mức: đến trước phục vụ trước
    }

    @Test
    void scoreRejectsAnUnknownPriority() {
        assertThatThrownBy(() -> QueueKeys.score(3, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueueKeys.score(-1, 1L)).isInstanceOf(IllegalArgumentException.class);
    }
}
