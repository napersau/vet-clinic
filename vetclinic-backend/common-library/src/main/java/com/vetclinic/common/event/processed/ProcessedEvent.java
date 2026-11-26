package com.vetclinic.common.event.processed;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Một dòng {@code processed_events} (schema_init.sql v4.0 mục 13): sự kiện {@code eventId} đã được listener {@code consumer}
 * xử lý xong. Ghi trong cùng giao dịch với thay đổi do sự kiện gây ra; trùng khóa = đã xử lý.
 * <p>
 * {@link Persistable#isNew()} luôn {@code true} để Spring Data gọi {@code persist} (INSERT) thay vì {@code merge}
 * (SELECT rồi UPDATE): hai bản nhận đồng thời thì bản sau vấp khóa chính và rollback, đúng ý muốn.
 */
@Entity
@Table(name = "processed_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent implements Persistable<ProcessedEventId> {

    @EmbeddedId
    private ProcessedEventId id;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    public ProcessedEvent(ProcessedEventId id, Instant processedAt) {
        this.id = id;
        this.processedAt = processedAt;
    }

    @Override
    @Transient
    public boolean isNew() {
        return true;
    }
}
