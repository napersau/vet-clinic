package com.vetclinic.clinical.queue;

import com.vetclinic.clinical.constant.QueueType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Yêu cầu phát một phiếu. {@code dedupeKey}: khóa chống phát trùng (thường {@code eventId + ":" + queueType}); {@code doctorId}: chỉ với DOCTOR,
 * null = danh sách chờ phân công; {@code priority}: 2 cấp cứu, 1 ca có kết quả CLS, 0 thường; {@code issuedAt}: mốc tính điểm (giờ thu tiền, hoặc giờ
 * tiếp đón / có kết quả với ca ưu tiên); {@code issuedBy}: thu ngân phát phiếu (null = hệ thống); {@code vsdIds}: các dòng dịch vụ được gộp vào phiếu.
 */
public record IssueTicketRequest(
        String dedupeKey,
        QueueType queueType,
        LocalDate serviceDate,
        UUID doctorId,
        UUID visitId,
        int priority,
        Instant issuedAt,
        UUID issuedBy,
        List<UUID> vsdIds,
        String petName,
        String ownerName,
        String roomNumber
) {
}
