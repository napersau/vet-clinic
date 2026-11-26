package com.vetclinic.auth.entity;

import com.vetclinic.common.security.RoleCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Danh mục vai trò (docs v4.0 mục 1): thêm vai trò mới chỉ cần INSERT, không ALTER TABLE. */
@Entity
@Table(name = "roles")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short id;

    @Enumerated(EnumType.STRING)
    @Column(name = "code", nullable = false, unique = true, length = 30)
    private RoleCode code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description")
    private String description;

    /** Vai trò lõi của hệ thống, không cho xóa. */
    @Column(name = "is_system", nullable = false)
    private boolean system;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
