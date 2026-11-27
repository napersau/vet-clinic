package com.vetclinic.clinical.queue;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.dto.response.TicketInfo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phiếu xếp hàng đọc từ Hash {@code ticket:{id}} trên Redis (SM-07): phiếu đang chờ hoặc đang phục vụ, không còn là entity JPA từ bước 6.2.
 * Hash chỉ chứa chuỗi; các trường thời gian là epoch millis, {@code vsdIds} là danh sách id dòng chỉ định phân tách bằng dấu phẩy.
 */
public record TicketView(
        UUID id,
        QueueType queueType,
        LocalDate serviceDate,
        int ticketNo,
        UUID visitId,
        UUID doctorId,
        int priority,
        TicketStatus status,
        Instant issuedAt,
        UUID issuedBy,
        int calledCount,
        Instant lastCalledAt,
        Instant startedAt,
        List<UUID> vsdIds,
        String petName,
        String ownerName,
        String roomNumber
) {

    public static TicketView from(UUID id, Map<String, String> h) {
        return new TicketView(id, QueueType.valueOf(h.get("queueType")), LocalDate.parse(h.get("serviceDate"), DateTimeFormatter.BASIC_ISO_DATE),
                intOf(h.get("ticketNo")), uuidOf(h.get("visitId")), uuidOf(h.get("doctorId")), intOf(h.get("priority")),
                TicketStatus.valueOf(h.getOrDefault("status", "WAITING")), instantOf(h.get("issuedAt")), uuidOf(h.get("issuedBy")),
                intOf(h.get("calledCount")), instantOf(h.get("lastCalledAt")), instantOf(h.get("startedAt")), uuidsOf(h.get("vsdIds")),
                blank(h.get("petName")), blank(h.get("ownerName")), blank(h.get("roomNumber")));
    }

    public TicketInfo info() {
        return new TicketInfo(id, queueType, ticketNo, priority, status, issuedAt, lastCalledAt, calledCount);
    }

    private static int intOf(String s) {
        return s == null || s.isBlank() ? 0 : Integer.parseInt(s.trim());
    }

    private static UUID uuidOf(String s) {
        return s == null || s.isBlank() ? null : UUID.fromString(s.trim());
    }

    private static Instant instantOf(String s) {
        return s == null || s.isBlank() ? null : Instant.ofEpochMilli(Long.parseLong(s.trim()));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static List<UUID> uuidsOf(String s) {
        List<UUID> ids = new ArrayList<>();
        if (s != null && !s.isBlank()) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    ids.add(UUID.fromString(part.trim()));
                }
            }
        }
        return ids;
    }
}
