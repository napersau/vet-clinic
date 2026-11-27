package com.vetclinic.clinical.service;

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
import com.vetclinic.clinical.dto.response.VisitHistoryResponse;
import com.vetclinic.clinical.dto.response.MedicalRecordResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.dto.response.VitalSignResponse;
import com.vetclinic.clinical.dto.response.VisitSummaryResponse;

import com.vetclinic.common.dto.PageResponse;
import java.time.LocalDate;
import java.util.UUID;
import java.util.Collection;
import java.util.List;

/** Vòng đời một đợt khám ngoại trú hai pha (SM-01). */
public interface VisitService {

    /** UC-19: tiếp nhận khách có hẹn (appointmentId) hoặc vãng lai (petId). Tạo đợt khám WAITING. */
    VisitResponse checkIn(CheckInRequest request);

    /** Danh sách đợt khám có lọc (bảng điều phối): trạng thái, ngày check-in theo giờ phòng khám, bác sĩ, thú cưng, mã ca. Mới nhất trước. */
    PageResponse<VisitResponse> search(VisitStatus status,
            LocalDate date, UUID doctorId, UUID petId, String keyword, int page, int size);

    VisitResponse getById(UUID id);

    List<VisitHistoryResponse> getHistory(UUID id);

    /**
     * UC-21, FR-24: Lễ tân xếp ca WAITING vào hàng đợi của Bác sĩ ACTIVE BẤT KỲ, bất cứ lúc nào, KHÔNG cần Bác sĩ rảnh. Gọi lại với
     * Bác sĩ khác = chuyển hàng đợi. Giữ nguyên queue_order (ca đến trước vẫn đứng trước), không đổi trạng thái nên không ghi lịch sử.
     * Chống xung đột (FR-25): Bác sĩ vừa bấm "Bắt đầu khám" ca này thì ca không còn WAITING nên bị từ chối.
     */
    VisitResponse assignDoctor(UUID id, AssignDoctorRequest request);

    /** UC-21 Luồng 5c: gỡ phân công (doctor_id = NULL), ca về danh sách chờ phân công; giữ nguyên queue_order. */
    VisitResponse unassignDoctor(UUID id);

    /**
     * T02, UC-22: Bác sĩ bắt đầu khám giai đoạn 1 (WAITING -> IN_PROGRESS). Chỉ Bác sĩ được gán (CLIN_1311) và không có ca
     * IN_PROGRESS khác (CLIN_1302). Tự tạo MỘT dòng dịch vụ khám (EXAM_GEN, IN_PROGRESS) kèm hóa đơn PENDING. Sinh hiệu tùy chọn.
     */
    VisitResponse startExam(UUID id, StartExamRequest request);

    /** Ghi một lần đo sinh hiệu (dòng mới) khi ca đang khám (IN_PROGRESS), Bác sĩ được gán. */
    VitalSignResponse recordVitalSigns(UUID id, VitalSignsRequest request);

    /** Các lần đo sinh hiệu của đợt khám, theo thứ tự thời gian. */
    List<VitalSignResponse> vitalSigns(UUID id);

    /** Sửa hồ sơ khám: Bác sĩ được gán, ca IN_PROGRESS; từ lúc chốt trở đi trả CLIN_1317. */
    MedicalRecordResponse updateMedicalRecord(UUID id, UpdateMedicalRecordRequest request);

    /**
     * T04, UC-23: chỉ định cận lâm sàng (chỉ LABORATORY / IMAGING). Yêu cầu đã có cân nặng và thân nhiệt (CLIN_1308), không còn
     * dịch vụ tại phòng dang dở (CLIN_1309), và đang ở Giai đoạn 1. Mỗi dịch vụ: dòng chỉ định PENDING (tự vào hàng đợi Lab / CĐHA)
     * + hóa đơn PENDING, cùng giao dịch; dịch vụ khám hoàn tất; ca IN_PROGRESS -> WAITING_RESULTS, Bác sĩ được giải phóng.
     */
    VisitResponse orderParaclinical(UUID id, ParaclinicalOrderRequest request);

    /**
     * FR-30: chỉ định dịch vụ tại phòng (tiêm, tẩy giun, thủ thuật...). Mỗi dịch vụ một dòng PENDING + một hóa đơn PENDING; ca vẫn
     * IN_PROGRESS. Bác sĩ bấm "Đã thực hiện" (POST /service-details/{id}/perform) để hoàn tất.
     */
    VisitResponse orderInRoomServices(UUID id, OrderServicesRequest request);

    /**
     * T07: Bác sĩ bắt đầu Giai đoạn 2 (đọc kết quả) sau khi gọi số và bệnh nhân đã vào phòng. RESULTS_READY -> IN_PROGRESS; đúng Bác sĩ
     * khám chính, và Bác sĩ không có ca IN_PROGRESS khác (CLIN_1302).
     */
    VisitResponse startPhase2(UUID id);

    /**
     * T05 / T09 (UC-33): chốt chuyên môn, khóa hồ sơ. IN_PROGRESS -> WAITING_PAYMENT. Cần chẩn đoán (CLIN_1307), cân nặng + thân nhiệt
     * (CLIN_1308), dịch vụ tại phòng đã xong hoặc hủy (CLIN_1309). KHÔNG gọi billing, KHÔNG phát event: hóa đơn dịch vụ đã lập từ lúc
     * chỉ định, thu ngân thu ở bảng kê hóa đơn (T10).
     */
    VisitResponse closeVisit(UUID id, CloseVisitRequest request);

    /**
     * T14 "Mở lại hồ sơ": WAITING_PAYMENT -> IN_PROGRESS bởi Bác sĩ phụ trách HOẶC Admin, lý do bắt buộc, Bác sĩ không có ca IN_PROGRESS
     * khác. Hóa đơn đã PAID giữ nguyên, hóa đơn PENDING còn nguyên; dịch vụ bỏ đi thì hủy kèm hóa đơn, dịch vụ thêm vào thì có hóa đơn
     * mới. Chốt lại bằng close-visit (T05 / T09). Không gọi billing.
     */
    VisitResponse reopen(UUID id, ReasonRequest request);

    /**
     * EX-11 "Khách bỏ về" (T08 từ RESULTS_READY, T12 từ WAITING_RESULTS): -> COMPLETED, không lập hóa đơn mới. Chỉ định cận lâm sàng còn
     * PENDING bị hủy kèm hóa đơn; dịch vụ đã làm giữ hóa đơn PENDING làm công nợ. Từ chối nếu còn dịch vụ cận lâm sàng IN_PROGRESS
     * (CLIN_1304). Phát VisitCompletedEvent (chỉ thông báo).
     */
    VisitResponse abandon(UUID id);

    /**
     * T11 "Hủy ca / Chuyển viện" ở Giai đoạn 1: IN_PROGRESS -> CANCELLED bởi Bác sĩ phụ trách, lý do bắt buộc. Chỉ khi chưa có dịch vụ tại
     * phòng COMPLETED; mọi dịch vụ còn dở bị hủy kèm hóa đơn CANCELLED.
     */
    VisitResponse cancelExam(UUID id, ReasonRequest request);

    /** T03 / EX-03: Lễ tân hủy tiếp nhận khi khách bỏ về lúc đang chờ. WAITING -> CANCELLED, lý do bắt buộc. */
    VisitResponse cancel(UUID id, ReasonRequest request);

    /** Danh sách ca của Bác sĩ đang đăng nhập: chờ vào phòng, đang khám, chờ tái khám. */
    List<VisitResponse> myVisits();

    // ------------------------------------------------------------------ không cần người dùng đăng nhập

    /** Cho billing-pharmacy-service: thông tin rút gọn của một đợt khám. */
    VisitSummaryResponse summary(UUID visitId);

    /** Như summary nhưng theo lô; id không tồn tại thì bị bỏ qua (dùng làm giàu danh sách). */
    List<VisitSummaryResponse> summaries(Collection<UUID> visitIds);
}
