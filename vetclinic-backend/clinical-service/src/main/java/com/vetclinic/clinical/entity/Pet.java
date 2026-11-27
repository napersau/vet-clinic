package com.vetclinic.clinical.entity;

import com.vetclinic.clinical.constant.PetGender;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Hồ sơ thú cưng (UUIDv7). {@code ownerNameSnapshot} / {@code ownerPhoneSnapshot} lưu tên và SĐT chủ nuôi ngay trên hồ sơ để danh sách tiếp
 * đón và tìm theo SĐT không phải gọi auth-service (cập nhật bất đồng bộ qua {@code UserProfileUpdatedEvent}). Xóa mềm bằng
 * {@code deletedAt}: hồ sơ khám cũ vẫn tham chiếu được.
 */
@Entity
@Table(name = "pets")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Pet {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /** Khóa ngoại logic -> pet_clinic_auth_db.users(id) */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "owner_name_snapshot", nullable = false, length = 150)
    private String ownerNameSnapshot;

    @Column(name = "owner_phone_snapshot", nullable = false, length = 20)
    private String ownerPhoneSnapshot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "species_id", nullable = false)
    private Species species;

    @Column(name = "pet_code", nullable = false, unique = true, length = 50)
    private String petCode;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "breed", length = 100)
    private String breed;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false, length = 10)
    @Builder.Default
    private PetGender gender = PetGender.UNKNOWN;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "color", length = 100)
    private String color;

    @Column(name = "is_neutered", nullable = false)
    private boolean neutered;

    /** Số vi mạch (duy nhất khi có). */
    @Column(name = "microchip_number", unique = true, length = 30)
    private String microchipNumber;

    @Column(name = "identifying_characteristics", columnDefinition = "TEXT")
    private String identifyingCharacteristics;

    /** Tiền sử dị ứng thuốc: hiển thị cảnh báo khi Bác sĩ kê đơn. */
    @Column(name = "allergies", columnDefinition = "TEXT")
    private String allergies;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Xóa mềm: khác null = đã xóa (ẩn khỏi danh sách, không đặt lịch / tiếp đón được). */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
