package com.vetclinic.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Hợp đồng tối thiểu của mọi sự kiện liên service (Quy chuẩn v4.0 mục 9.2): {@code eventId} (UUIDv7, sinh khi tạo sự kiện,
 * consumer dùng để chống xử lý trùng qua {@code processed_events}) và thời điểm phát sinh.
 */
public interface BaseEvent {

    UUID eventId();

    Instant occurredAt();
}
