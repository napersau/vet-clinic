package com.vetclinic.clinical.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;

/** Khoảng tham chiếu của một chỉ số theo loài. Khóa chính tự nhiên (chỉ số, loài): mỗi cặp có đúng một khoảng. */
@Entity
@Table(name = "lab_test_reference_ranges")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabTestReferenceRange {

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        @Column(name = "lab_test_item_id")
        private Short labTestItemId;

        @Column(name = "species_id")
        private Short speciesId;
    }

    @EmbeddedId
    private Key id;

    @MapsId("labTestItemId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lab_test_item_id", nullable = false)
    private LabTestItem labTestItem;

    @MapsId("speciesId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "species_id", nullable = false)
    private Species species;

    @Column(name = "min_value", nullable = false, precision = 8, scale = 2)
    private BigDecimal minValue;

    @Column(name = "max_value", nullable = false, precision = 8, scale = 2)
    private BigDecimal maxValue;
}
