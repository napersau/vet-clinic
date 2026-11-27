package com.vetclinic.reporting.repository;

import com.vetclinic.reporting.entity.DailyRevenueSummary;
import com.vetclinic.reporting.entity.DailyRevenueSummaryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface DailyRevenueSummaryRepository extends JpaRepository<DailyRevenueSummary, DailyRevenueSummaryId> {

    @Modifying
    @Query("DELETE FROM DailyRevenueSummary s WHERE s.businessDate >= :from")
    int deleteFromDate(@Param("from") LocalDate from);

    /** Các dòng tổng hợp trong khoảng ngày (cả hai đầu), thứ tự ổn định để giao diện vẽ biểu đồ. */
    List<DailyRevenueSummary> findByBusinessDateBetweenOrderByBusinessDateAscRevenueTypeAscCategoryCodeAsc(LocalDate from, LocalDate to);

    /**
     * Cộng dồn nguyên tử (một câu lệnh, không đọc rồi ghi): dòng chưa có thì chèn, đã có thì cộng. Số âm (hoàn tiền) trừ đi.
     * Gọi trong CÙNG giao dịch với việc ghi {@code revenue_facts}. {@code lineCount} là +1 (SALE) hoặc -1 (REFUND).
     */
    @Modifying
    @Query(value = """
            INSERT INTO daily_revenue_summaries (business_date, revenue_type, category_code, category_name, line_count,
                                                 amount_before_vat, vat_amount, discount_amount, net_amount)
            VALUES (:businessDate, :revenueType, :categoryCode, :categoryName, :lineCount, :amountBeforeVat, :vatAmount, :discountAmount, :netAmount) AS new
            ON DUPLICATE KEY UPDATE
                category_name = new.category_name,
                line_count = daily_revenue_summaries.line_count + new.line_count,
                amount_before_vat = daily_revenue_summaries.amount_before_vat + new.amount_before_vat,
                vat_amount = daily_revenue_summaries.vat_amount + new.vat_amount,
                discount_amount = daily_revenue_summaries.discount_amount + new.discount_amount,
                net_amount = daily_revenue_summaries.net_amount + new.net_amount
            """, nativeQuery = true)
    int accumulate(@Param("businessDate") LocalDate businessDate, @Param("revenueType") String revenueType,
                   @Param("categoryCode") String categoryCode, @Param("categoryName") String categoryName,
                   @Param("lineCount") int lineCount, @Param("amountBeforeVat") BigDecimal amountBeforeVat,
                   @Param("vatAmount") BigDecimal vatAmount, @Param("discountAmount") BigDecimal discountAmount,
                   @Param("netAmount") BigDecimal netAmount);
}
