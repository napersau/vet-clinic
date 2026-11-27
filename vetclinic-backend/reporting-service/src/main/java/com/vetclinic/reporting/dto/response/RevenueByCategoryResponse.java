package com.vetclinic.reporting.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** {@code GET /reports/revenue/by-category}: doanh thu theo nhóm dịch vụ / nhóm thuốc trong một tháng (hoặc khoảng ngày), đã trừ hoàn tiền. */
public record RevenueByCategoryResponse(
        String month,                // yyyy-MM khi hỏi theo tháng, null khi hỏi theo khoảng ngày
        LocalDate fromDate,
        LocalDate toDate,
        RevenueDailyResponse.Totals totals,
        List<Item> items
) {

    public record Item(
            String revenueType,
            String categoryCode,
            String categoryName,
            long lineCount,
            BigDecimal amountBeforeVat,
            BigDecimal vatAmount,
            BigDecimal discountAmount,
            BigDecimal netAmount
    ) {
    }
}
