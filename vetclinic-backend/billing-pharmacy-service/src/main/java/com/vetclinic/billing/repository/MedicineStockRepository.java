package com.vetclinic.billing.repository;

import com.vetclinic.billing.entity.MedicineStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MedicineStockRepository extends JpaRepository<MedicineStock, UUID> {

    /**
     * Trừ kho nguyên tử (quy chuẩn §8.1): điều kiện tồn kho nằm ngay trong câu UPDATE nên hai giao dịch đồng thời không thể cùng trừ vượt số
     * còn lại. Trả 0 dòng = không đủ hàng (hoặc thuốc không có dòng tồn kho).
     * <p>
     * Cố ý KHÔNG clearAutomatically: nó sẽ tách rời các entity đang xử lý khỏi persistence context. Nơi gọi dùng
     * {@code EntityManager.refresh(medicine)} để đọc lại tồn kho thật, không bao giờ tin giá trị cũ trong entity.
     */
    @Modifying(flushAutomatically = true)
    @Query("update MedicineStock s set s.quantityOnHand = s.quantityOnHand - :quantity, s.version = s.version + 1 "
            + "where s.medicineId = :id and s.quantityOnHand >= :quantity")
    int deduct(@Param("id") UUID id, @Param("quantity") int quantity);

    /** Nhập kho / cân chỉnh (delta có thể âm). Không cho tồn kho âm: trả 0 dòng nếu sẽ âm hoặc thuốc không có dòng tồn kho. */
    @Modifying(flushAutomatically = true)
    @Query("update MedicineStock s set s.quantityOnHand = s.quantityOnHand + :delta, s.version = s.version + 1 "
            + "where s.medicineId = :id and s.quantityOnHand + :delta >= 0")
    int add(@Param("id") UUID id, @Param("delta") int delta);
}
