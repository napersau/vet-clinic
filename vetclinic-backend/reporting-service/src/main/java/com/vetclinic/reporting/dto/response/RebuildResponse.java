package com.vetclinic.reporting.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Kết quả job dựng lại báo cáo (Task 4.5.4). {@code fetchedByType}: số sự kiện đọc được từ outbox của các service nguồn theo kiểu sự kiện;
 * {@code applied}: số sự kiện đã áp dụng; {@code skipped}: số sự kiện không áp dụng được (kèm lý do đầu tiên trong {@code skippedReasons}).
 */
public record RebuildResponse(
        LocalDate fromDate,
        int days,
        Map<String, Integer> fetchedByType,
        int fetched,
        int applied,
        int skipped,
        List<String> skippedReasons
) {
}
