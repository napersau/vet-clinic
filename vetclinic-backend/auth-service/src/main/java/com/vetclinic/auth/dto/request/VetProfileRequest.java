package com.vetclinic.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Hồ sơ chuyên môn thú y (docs v4.0 mục 6). Bắt buộc khi tạo tài khoản có vai trò VETERINARIAN. */
public record VetProfileRequest(
        @NotBlank(message = "Số chứng chỉ hành nghề không được để trống")
        @Size(max = 50, message = "Số chứng chỉ tối đa 50 ký tự")
        String licenseNumber,
        @NotNull(message = "Ngày cấp chứng chỉ không được để trống")
        LocalDate licenseIssuedDate,
        @NotBlank(message = "Cơ quan cấp chứng chỉ không được để trống")
        @Size(max = 150, message = "Cơ quan cấp tối đa 150 ký tự")
        String licenseIssuedBy,
        LocalDate licenseExpiryDate,
        @NotBlank(message = "Học vị không được để trống")
        @Size(max = 50, message = "Học vị tối đa 50 ký tự")
        String academicDegree,
        @Size(max = 150, message = "Chuyên khoa tối đa 150 ký tự")
        String specialty,
        @Size(max = 150, message = "Nơi tốt nghiệp tối đa 150 ký tự")
        String graduatedFrom,
        Short careerStartYear,
        String biography
) {
}
