package com.vetclinic.clinical.dto.response;

import com.vetclinic.clinical.constant.VisitStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Đợt khám. services / labResults / imagingResults / vitalSigns / medicalRecord chỉ có ở API chi tiết, null ở các API danh sách
 * (tránh N+1). locked = true từ lúc Bác sĩ chốt chuyên môn (WAITING_PAYMENT trở đi): không được sửa hồ sơ khám, dịch vụ, đơn
 * thuốc (BR-27).
 * <p>
 * Lược đồ v4.0: {@code pet_visits} chỉ giữ thông tin hành chính. Sinh hiệu ở {@code vital_signs} (nhiều lần đo) và nội dung chuyên
 * môn ở {@code medical_records}. Các mốc (bắt đầu, chốt, hoàn tất) và lý do hủy SUY RA từ lịch sử trạng thái; phòng khám của Bác sĩ
 * lấy từ auth-service ở tầng controller. doctorName là bản chụp (snapshot) trên đợt khám; doctorActive (EX-17) cũng do tầng controller gắn từ
 * auth-service: false = Bác sĩ đã bị khóa / ngừng (giao diện hiện nhãn "Đã khóa" cạnh tên), null = chưa biết (auth-service tạm sập hoặc chưa có Bác sĩ).
 * ticket: phiếu xếp hàng còn hiệu lực của ca (chỉ gắn ở các API hàng đợi / chi tiết; null ở chỗ khác).
 */
public record VisitResponse(
        UUID id,
        String visitCode,
        UUID petId,
        String petName,
        String petCode,
        String speciesName,
        UUID ownerId,
        UUID appointmentId,
        UUID doctorId,
        String doctorName,
        Boolean doctorActive,
        UUID receptionistId,
        String assignedRoom,
        boolean emergency,
        VisitStatus status,
        boolean locked,
        TicketInfo ticket,
        String reasonForVisit,
        String cancelReason,
        Instant checkedInAt,
        Instant startedAt,
        Instant closedAt,
        Instant completedAt,
        List<ServiceDetailResponse> services,
        List<LabResultResponse> labResults,
        List<ImagingResultResponse> imagingResults,
        List<VitalSignResponse> vitalSigns,
        MedicalRecordResponse medicalRecord
) {

    /** Gắn số phòng của Bác sĩ phụ trách (users.room_number): lấy từ auth-service ở tầng controller, ngoài transaction. */
    public VisitResponse withAssignedRoom(String room) {
        return new VisitResponse(id, visitCode, petId, petName, petCode, speciesName, ownerId, appointmentId, doctorId, doctorName, doctorActive,
                receptionistId, room, emergency, status, locked, ticket, reasonForVisit, cancelReason, checkedInAt,
                startedAt, closedAt, completedAt, services, labResults, imagingResults, vitalSigns, medicalRecord);
    }

    /** Gắn số phòng và trạng thái hoạt động của Bác sĩ phụ trách (cùng một lần tra auth-service theo lô). */
    public VisitResponse withDoctorInfo(String room, Boolean active) {
        return new VisitResponse(id, visitCode, petId, petName, petCode, speciesName, ownerId, appointmentId, doctorId, doctorName, active,
                receptionistId, room, emergency, status, locked, ticket, reasonForVisit, cancelReason, checkedInAt,
                startedAt, closedAt, completedAt, services, labResults, imagingResults, vitalSigns, medicalRecord);
    }

    /** Gắn phiếu xếp hàng (số in trên phiếu, ưu tiên, giờ gọi) cho màn hình hàng đợi. */
    public VisitResponse withTicket(TicketInfo t) {
        return new VisitResponse(id, visitCode, petId, petName, petCode, speciesName, ownerId, appointmentId, doctorId, doctorName, doctorActive,
                receptionistId, assignedRoom, emergency, status, locked, t, reasonForVisit, cancelReason, checkedInAt,
                startedAt, closedAt, completedAt, services, labResults, imagingResults, vitalSigns, medicalRecord);
    }

    /** Gắn sinh hiệu và hồ sơ khám (chỉ API chi tiết). */
    public VisitResponse withClinical(List<VitalSignResponse> vitals, MedicalRecordResponse record) {
        return new VisitResponse(id, visitCode, petId, petName, petCode, speciesName, ownerId, appointmentId, doctorId, doctorName, doctorActive,
                receptionistId, assignedRoom, emergency, status, locked, ticket, reasonForVisit, cancelReason, checkedInAt,
                startedAt, closedAt, completedAt, services, labResults, imagingResults, vitals, record);
    }
}
