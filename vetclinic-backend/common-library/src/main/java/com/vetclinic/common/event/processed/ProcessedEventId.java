package com.vetclinic.common.event.processed;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.UUID;

/** Khóa chính ghép {@code (event_id, consumer)}: một sự kiện có thể được nhiều listener của cùng service xử lý. */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ProcessedEventId implements Serializable {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "event_id", nullable = false, columnDefinition = "binary(16)")
    private UUID eventId;

    @Column(name = "consumer", nullable = false, length = 100)
    private String consumer;
}
