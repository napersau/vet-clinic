package com.vetclinic.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Tồn kho hiện tại, 1-1 với thuốc (docs v4.0 mục 42), tách khỏi {@link Medicine} vì dòng này bị khóa / cập nhật liên tục khi bán. Phi chuẩn hóa CÓ
 * CHỦ ĐÍCH: {@code quantityOnHand = SUM(inventory_transactions.quantity)}, lưu để trừ kho nguyên tử
 * ({@code UPDATE ... SET quantity_on_hand = quantity_on_hand - :qty WHERE quantity_on_hand >= :qty}).
 * <p>
 * Chỉ đổi bằng các câu UPDATE trong MedicineStockRepository; không bao giờ gán {@code quantityOnHand} rồi save().
 */
@Entity
@Table(name = "medicine_stocks")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicineStock {

    /** Cũng là khóa ngoại tới medicines(id) ({@code @MapsId}). */
    @Id
    @Column(name = "medicine_id")
    private UUID medicineId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "medicine_id")
    private Medicine medicine;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
