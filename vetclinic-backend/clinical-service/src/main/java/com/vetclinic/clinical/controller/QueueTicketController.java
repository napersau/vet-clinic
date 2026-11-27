package com.vetclinic.clinical.controller;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.dto.response.QueueTicketResponse;
import com.vetclinic.clinical.queue.QueueRebuildService;
import com.vetclinic.clinical.service.QueueTicketService;
import com.vetclinic.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Phiếu xếp hàng (SM-07, BR-34): gọi / gọi số tiếp theo / bắt đầu phiếu phòng Xét nghiệm, CĐHA. "Gọi" chỉ cập nhật last_called_at
 * và phát PatientCalledEvent, không đổi trạng thái hay thứ tự. Phiếu phòng Bác sĩ bắt đầu bằng start-exam / start-phase2.
 */
@RestController
@RequestMapping("/api/v1/clinical/queue-tickets")
@RequiredArgsConstructor
public class QueueTicketController {

    private final QueueTicketService queueTicketService;
    private final QueueRebuildService rebuildService;

    /** "Gọi số tiếp theo": phiếu đứng đầu hàng đợi của người đang thao tác (CLIN_1303 nếu trống, CLIN_1302 nếu Bác sĩ đang khám dở). */
    @PostMapping("/call-next")
    @PreAuthorize("hasAnyRole('VETERINARIAN','LAB_TECHNICIAN','IMAGING_TECHNICIAN')")
    public ResponseEntity<ApiResponse<QueueTicketResponse>> callNext(@RequestParam QueueType queueType) {
        return ResponseEntity.ok(ApiResponse.success("Đã gọi số tiếp theo", queueTicketService.callNext(queueType)));
    }

    /** "Gọi" / "Gọi lại" một phiếu bất kỳ (khách vắng thì gọi phiếu khác, thứ tự giữ nguyên; khách quay lại thì gọi lại). */
    @PostMapping("/{id}/call")
    @PreAuthorize("hasAnyRole('VETERINARIAN','LAB_TECHNICIAN','IMAGING_TECHNICIAN')")
    public ResponseEntity<ApiResponse<QueueTicketResponse>> call(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Đã gọi bệnh nhân", queueTicketService.call(id)));
    }

    /**
     * Quản trị viên dựng lại hàng đợi trên Redis từ MySQL (sau khi Redis bị xóa / mất dữ liệu / sự kiện vào DLQ): phát các phiếu còn thiếu theo thứ tự giờ thu
     * tiền, idempotent (không phát trùng phiếu đã có). Redis không truy cập được -> CLIN_1318.
     */
    @PostMapping("/rebuild")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<QueueRebuildService.Result>> rebuild() {
        QueueRebuildService.Result result = rebuildService.rebuild(java.time.Duration.ZERO);
        return ResponseEntity.ok(ApiResponse.success(result.ran() ? "Đã dựng lại hàng đợi từ MySQL" : "Đang có lần dựng khác chạy, bỏ qua", result));
    }

    /** Phòng XN / CĐHA: thú cưng đã vào phòng, bắt đầu mọi dịch vụ của phiếu. */
    @PostMapping("/{id}/start")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN','IMAGING_TECHNICIAN')")
    public ResponseEntity<ApiResponse<QueueTicketResponse>> start(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Đã bắt đầu thực hiện", queueTicketService.start(id)));
    }
}
