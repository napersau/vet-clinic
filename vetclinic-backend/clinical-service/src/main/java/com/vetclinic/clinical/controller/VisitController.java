package com.vetclinic.clinical.controller;

import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.request.AssignDoctorRequest;
import com.vetclinic.clinical.dto.request.CheckInRequest;
import com.vetclinic.clinical.dto.request.CloseVisitRequest;
import com.vetclinic.clinical.dto.request.OrderServicesRequest;
import com.vetclinic.clinical.dto.request.ParaclinicalOrderRequest;
import com.vetclinic.clinical.dto.request.ReasonRequest;
import com.vetclinic.clinical.dto.request.StartExamRequest;
import com.vetclinic.clinical.dto.request.UpdateMedicalRecordRequest;
import com.vetclinic.clinical.dto.request.VitalSignsRequest;
import com.vetclinic.clinical.dto.response.QueueBoardResponse;
import com.vetclinic.clinical.dto.response.VisitHistoryResponse;
import com.vetclinic.clinical.dto.response.MedicalRecordResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.dto.response.VitalSignResponse;
import com.vetclinic.clinical.service.DoctorQueueService;
import com.vetclinic.clinical.service.VisitEnricher;
import com.vetclinic.clinical.service.VisitService;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.dto.PageResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.List;

/** Đợt khám ngoại trú hai pha. Chuyển trạng thái dùng POST theo quy chuẩn Action Endpoint. */
@RestController
@RequestMapping("/api/v1/clinical/pet-visits")
@RequiredArgsConstructor
public class VisitController {

    private final VisitService visitService;
    private final DoctorQueueService doctorQueueService;
    /** Số phòng của Bác sĩ lấy từ auth-service SAU commit (pet_visits không còn cột assigned_room). */
    private final VisitEnricher enricher;

    /** UC-19: tiếp nhận khách có hẹn (appointmentId) hoặc vãng lai (petId). */
    @PostMapping("/check-in")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public ResponseEntity<ApiResponse<VisitResponse>> checkIn(@Valid @RequestBody CheckInRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Tiếp nhận thành công", enricher.enrich(visitService.checkIn(request))));
    }

    /**
     * UC-20, FR-21: bảng điều phối: danh sách chờ phân công + hàng đợi riêng từng Bác sĩ (kèm số người chờ, trạng thái, phòng) và
     * Bác sĩ được gợi ý (ít phiếu chờ nhất), kèm mục "chờ thu phí khám". Hàng đợi là phiếu xếp hàng: priority DESC, issued_at.
     */
    @GetMapping("/queue")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<QueueBoardResponse>> queue() {
        return ResponseEntity.ok(ApiResponse.success(doctorQueueService.board()));
    }

    /** Hàng đợi của chính Bác sĩ đang đăng nhập: các ca có phiếu DOCTOR đang chờ (đã nộp phí khám / cấp cứu / có kết quả CLS). */
    @GetMapping("/my-queue")
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<List<VisitResponse>>> myQueue() {
        return ResponseEntity.ok(ApiResponse.success(doctorQueueService.myQueue()));
    }

    /** Danh sách đợt khám có lọc, phân trang, mới nhất trước (bảng điều phối của lễ tân, lịch sử ca của bác sĩ). */
    @GetMapping
    @PreAuthorize("hasAnyRole('RECEPTIONIST','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<VisitResponse>>> search(
            @RequestParam(required = false) VisitStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID doctorId,
            @RequestParam(required = false) UUID petId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // NFR-10: danh sách chỉ dùng cột snapshot (tên Bác sĩ, thú cưng, chủ nuôi), KHÔNG gọi auth-service; nhãn "Đã khóa" (EX-17) và số phòng có ở chi tiết một ca
        return ResponseEntity.ok(ApiResponse.success(visitService.search(status, date, doctorId, petId, keyword, page, size)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PET_OWNER','RECEPTIONIST','VETERINARIAN','LAB_TECHNICIAN','IMAGING_TECHNICIAN','ADMIN')")
    public ResponseEntity<ApiResponse<VisitResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(enricher.enrich(visitService.getById(id))));
    }

    /** Audit trail vòng đời đợt khám. */
    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<List<VisitHistoryResponse>>> history(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(visitService.getHistory(id)));
    }

    /** UC-21, FR-24: xếp ca vào hàng đợi của Bác sĩ bất kỳ (không cần rảnh); gọi lại với Bác sĩ khác = chuyển hàng đợi. */
    @PostMapping("/{id}/assign-doctor")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public ResponseEntity<ApiResponse<VisitResponse>> assign(@PathVariable UUID id,
                                                             @Valid @RequestBody AssignDoctorRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Phân công bác sĩ thành công", enricher.enrich(visitService.assignDoctor(id, request))));
    }

    /** UC-21 Luồng 5c: gỡ phân công, ca về danh sách chờ phân công. */
    @PostMapping("/{id}/unassign-doctor")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public ResponseEntity<ApiResponse<VisitResponse>> unassignDoctor(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Đã gỡ phân công", enricher.enrich(visitService.unassignDoctor(id))));
    }

    /** UC-22 (T02): Bác sĩ bắt đầu khám; tạo hồ sơ khám, có thể ghi luôn lần đo sinh hiệu đầu tiên và bệnh sử. */
    @PostMapping("/{id}/start-exam")
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<VisitResponse>> startExam(@PathVariable UUID id,
                                                                @Valid @RequestBody StartExamRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Bác sĩ đã bắt đầu khám lâm sàng", enricher.enrich(visitService.startExam(id, request))));
    }

    /** FR-29: ghi MỘT lần đo sinh hiệu (đo lại thì thêm dòng mới, không ghi đè). Bác sĩ được gán, ca đang khám. */
    @PostMapping("/{id}/vital-signs")
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<VitalSignResponse>> recordVitalSigns(@PathVariable UUID id,
                                                                           @Valid @RequestBody VitalSignsRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Đã ghi sinh hiệu", visitService.recordVitalSigns(id, request)));
    }

    /** Các lần đo sinh hiệu của đợt khám, theo thứ tự thời gian. */
    @GetMapping("/{id}/vital-signs")
    @PreAuthorize("hasAnyRole('VETERINARIAN','RECEPTIONIST','ADMIN')")
    public ResponseEntity<ApiResponse<List<VitalSignResponse>>> vitalSigns(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(visitService.vitalSigns(id)));
    }

    /** FR-29: sửa hồ sơ khám (bệnh sử, kết quả khám, chẩn đoán...). Chỉ Bác sĩ phụ trách; khóa từ lúc chốt (CLIN_1317); có khóa lạc quan. */
    @PutMapping("/{id}/medical-record")
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<MedicalRecordResponse>> medicalRecord(@PathVariable UUID id,
                                                                            @Valid @RequestBody UpdateMedicalRecordRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã cập nhật hồ sơ khám", visitService.updateMedicalRecord(id, request)));
    }

    /**
     * T04, UC-23: chỉ định cận lâm sàng ({serviceIds, notes}). Cần có cân nặng + thân nhiệt (CLIN_1308) và không còn dịch vụ tại
     * phòng dở dang (CLIN_1309); ca sang WAITING_RESULTS và Bác sĩ được giải phóng. Mỗi dịch vụ sinh một hóa đơn dịch vụ PENDING.
     */
    @PostMapping("/{id}/paraclinical-orders")
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<VisitResponse>> paraclinicalOrders(@PathVariable UUID id,
                                                                         @Valid @RequestBody ParaclinicalOrderRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã chỉ định cận lâm sàng", enricher.enrich(visitService.orderParaclinical(id, request))));
    }

    /** FR-30: chỉ định dịch vụ tại phòng (tiêm, tẩy giun, thủ thuật...), mỗi dịch vụ một hóa đơn PENDING; ca vẫn IN_PROGRESS. */
    @PostMapping("/{id}/in-room-services")
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<VisitResponse>> inRoomServices(@PathVariable UUID id,
                                                                     @Valid @RequestBody OrderServicesRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã chỉ định dịch vụ tại phòng", enricher.enrich(visitService.orderInRoomServices(id, request))));
    }

    /** T07: bệnh nhân đã vào phòng sau khi gọi số: bắt đầu Giai đoạn 2 (đọc kết quả cận lâm sàng). */
    @PostMapping("/{id}/start-phase2")
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<VisitResponse>> startPhase2(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Đã bắt đầu khám Giai đoạn 2", enricher.enrich(visitService.startPhase2(id))));
    }

    /** T14 "Mở lại hồ sơ" (chưa thu đủ tiền) để điều chỉnh chuyên môn: Bác sĩ phụ trách hoặc Admin. Bắt buộc lý do. */
    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAnyRole('VETERINARIAN','ADMIN')")
    public ResponseEntity<ApiResponse<VisitResponse>> reopen(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã mở lại đợt khám", enricher.enrich(visitService.reopen(id, request))));
    }

    /** UC-33: chốt chuyên môn, khóa hồ sơ, chuyển sang thanh toán. Bắt buộc có chẩn đoán xác định. */
    @PostMapping("/{id}/close-visit")
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<VisitResponse>> closeVisit(@PathVariable UUID id,
                                                                 @Valid @RequestBody CloseVisitRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã chốt đợt khám, chuyển sang thanh toán", enricher.enrich(visitService.closeVisit(id, request))));
    }

    /** EX-11 (T08 / T12): khách bỏ về giữa chừng hoặc không đọc kết quả: đóng ca, ghi công nợ. */
    @PostMapping("/{id}/abandon")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','VETERINARIAN')")
    public ResponseEntity<ApiResponse<VisitResponse>> abandon(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Đã đóng ca, ghi nợ viện phí", enricher.enrich(visitService.abandon(id))));
    }

    /** T11 "Hủy ca / Chuyển viện" ở Giai đoạn 1 (không thu phí). Bắt buộc lý do. */
    @PostMapping("/{id}/cancel-exam")
    @PreAuthorize("hasRole('VETERINARIAN')")
    public ResponseEntity<ApiResponse<VisitResponse>> cancelExam(@PathVariable UUID id,
                                                                 @Valid @RequestBody ReasonRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã hủy ca khám", enricher.enrich(visitService.cancelExam(id, request))));
    }

    /** T03 / EX-03: Lễ tân hủy tiếp nhận khi khách bỏ về lúc đang chờ khám. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public ResponseEntity<ApiResponse<VisitResponse>> cancel(@PathVariable UUID id,
                                                             @Valid @RequestBody ReasonRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Đã hủy tiếp nhận", enricher.enrich(visitService.cancel(id, request))));
    }
}
