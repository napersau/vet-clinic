package com.vetclinic.reporting.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /reports/revenue/daily}: doanh thu từng ngày, tách theo loại (SERVICE / MEDICINE), đọc từ {@code daily_revenue_summaries}.
 * Mọi số là SUM(net_amount ...) nên ĐÃ trừ hoàn tiền (dòng REFUND âm). Gom theo tuần / tháng / quý do giao diện làm từ các dòng này.
 */
public record RevenueDailyResponse(
        LocalDate fromDate,
        LocalDate toDate,
        String type,                 // null = cả hai loại
        Totals totals,
        List<Item> items
) {

    public record Item(
            LocalDate businessDate,
            String revenueType,
            long lineCount,
            BigDecimal amountBeforeVat,
            BigDecimal vatAmount,
            BigDecimal discountAmount,
            BigDecimal netAmount
    ) {
    }

    public record Totals(
            long lineCount,
            BigDecimal amountBeforeVat,
            BigDecimal vatAmount,
            BigDecimal discountAmount,
            BigDecimal netAmount,
            BigDecimal serviceNetAmount,
            BigDecimal medicineNetAmount
    ) {
    }
}
