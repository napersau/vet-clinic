package com.vetclinic.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Hồ sơ chuyên môn thú y (docs v4.0 mục 6), quan hệ 1-1 với {@link StaffProfile}. Bắt buộc với tài khoản có vai trò VETERINARIAN
 * (thiếu thì {@code AUTH_1108}); tùy chọn với Bác sĩ / KTV Xét nghiệm, CĐHA có chứng chỉ.
 */
@Entity
@Table(name = "veterinarian_profiles")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VeterinarianProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private StaffProfile staff;

    /** Số chứng chỉ hành nghề thú y (duy nhất). */
    @Column(name = "license_number", nullable = false, unique = true, length = 50)
    private String licenseNumber;

    @Column(name = "license_issued_date", nullable = false)
    private LocalDate licenseIssuedDate;

    /** Cơ quan cấp (Chi cục Chăn nuôi và Thú y...). */
    @Column(name = "license_issued_by", nullable = false, length = 150)
    private String licenseIssuedBy;

    @Column(name = "license_expiry_date")
    private LocalDate licenseExpiryDate;

    /** Bác sĩ thú y / Thạc sĩ / Tiến sĩ. */
    @Column(name = "academic_degree", nullable = false, length = 50)
    private String academicDegree;

    @Column(name = "specialty", length = 150)
    private String specialty;

    @Column(name = "graduated_from", length = 150)
    private String graduatedFrom;

    /** Số năm kinh nghiệm = năm hiện tại - career_start_year. */
    @Column(name = "career_start_year")
    private Short careerStartYear;

    /** Giới thiệu hiển thị cho khách khi đặt lịch. */
    @Column(name = "biography", columnDefinition = "TEXT")
    private String biography;
}
