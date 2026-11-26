package com.vetclinic.appointment.mapper;

import com.vetclinic.appointment.dto.response.AppointmentHistoryResponse;
import com.vetclinic.appointment.dto.response.AppointmentResponse;
import com.vetclinic.appointment.entity.Appointment;
import com.vetclinic.appointment.entity.AppointmentStatusHistory;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.WARN)
public interface AppointmentMapper {

    // petName / ownerName / ownerPhone đọc từ 3 cột snapshot của appointments (v4.0). Các trường còn lại không phải cột nên được gắn sau:
    //  - endTime: AppointmentServiceImpl (start + độ dài khung)
    //  - cancelReason, confirmedBy/At, cancelledBy/At, updatedAt: AppointmentEnricher (appointment_status_histories)
    @Mapping(target = "petName", source = "petNameSnapshot")
    @Mapping(target = "ownerName", source = "ownerNameSnapshot")
    @Mapping(target = "ownerPhone", source = "ownerPhoneSnapshot")
    @Mapping(target = "endTime", ignore = true)
    @Mapping(target = "cancelReason", ignore = true)
    @Mapping(target = "confirmedBy", ignore = true)
    @Mapping(target = "confirmedAt", ignore = true)
    @Mapping(target = "cancelledBy", ignore = true)
    @Mapping(target = "cancelledAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    AppointmentResponse toResponse(Appointment appointment);

    AppointmentHistoryResponse toHistoryResponse(AppointmentStatusHistory history);
}
