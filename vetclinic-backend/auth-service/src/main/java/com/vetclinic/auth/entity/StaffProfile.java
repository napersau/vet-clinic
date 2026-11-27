package com.vetclinic.auth.entity;

import jakarta.persistence.CascadeType;
import com.vetclinic.auth.constant.EmploymentType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
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

/** Hồ sơ nhân sự, chỉ nhân viên phòng khám (docs v4.0 mục 5), quan hệ 1-1 với {@link User}. */
@Entity
@Table(name = "staff_profiles")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "employee_code", nullable = false, unique = true, length = 20)
    private String employeeCode;

    /** Số CCCD. */
    @Column(name = "citizen_id", unique = true, length = 20)
    private String citizenId;

    /** Chức danh: Bác sĩ thú y, Lễ tân, KTV xét nghiệm... */
    @Column(name = "position_title", nullable = false, length = 100)
    private String positionTitle;

    /** Bộ phận: Khám bệnh, Xét nghiệm, Chẩn đoán hình ảnh, Tiếp đón... */
    @Column(name = "department", length = 100)
    private String department;

    /** Phòng làm việc cố định (Bác sĩ / KTV). */
    @Column(name = "room_number", length = 20)
    private String roomNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", nullable = false, length = 20)
    @Builder.Default
    private EmploymentType employmentType = EmploymentType.FULL_TIME;

    @Column(name = "hire_date", nullable = false)
    private LocalDate hireDate;

    @Column(name = "termination_date")
    private LocalDate terminationDate;

    @Column(name = "emergency_contact_name", length = 150)
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone", length = 20)
    private String emergencyContactPhone;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** Hồ sơ chuyên môn thú y; null nếu nhân sự không có chứng chỉ (bắt buộc với vai trò VETERINARIAN). */
    @OneToOne(mappedBy = "staff", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private VeterinarianProfile vetProfile;
}
