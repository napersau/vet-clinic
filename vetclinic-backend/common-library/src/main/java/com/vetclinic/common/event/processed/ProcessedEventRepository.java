package com.vetclinic.common.event.processed;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {

    /**
     * Quên các sự kiện đã xử lý (mọi listener) để phát lại có chủ đích, vd. dựng lại mô hình đọc báo cáo (Task 4.5.4). Gọi theo lô nhỏ.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM ProcessedEvent p WHERE p.id.eventId IN :eventIds")
    int deleteByEventIds(@org.springframework.data.repository.query.Param("eventIds") java.util.Collection<java.util.UUID> eventIds);
}
