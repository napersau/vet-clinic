package com.vetclinic.auth.entity;

import com.vetclinic.auth.constant.RegisteredChannel;
import com.vetclinic.auth.constant.ContactChannel;
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
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Hồ sơ khách hàng / chủ nuôi (docs v4.0 mục 7), quan hệ 1-1 với {@link User}. */
@Entity
@Table(name = "pet_owner_profiles")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PetOwnerProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "customer_code", nullable = false, unique = true, length = 20)
    private String customerCode;

    /** ONLINE (tự đăng ký) | AT_CLINIC (Lễ tân / Admin tạo tại quầy). */
    @Enumerated(EnumType.STRING)
    @Column(name = "registered_channel", nullable = false, length = 20)
    @Builder.Default
    private RegisteredChannel registeredChannel = RegisteredChannel.ONLINE;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_contact", nullable = false, length = 20)
    @Builder.Default
    private ContactChannel preferredContact = ContactChannel.PHONE;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
