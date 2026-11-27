package com.vetclinic.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Nhóm thuốc (danh mục, thay cho ENUM): kháng sinh, vắc-xin, thuốc trị ký sinh trùng... */
@Entity
@Table(name = "medicine_categories")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicineCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short id;

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
