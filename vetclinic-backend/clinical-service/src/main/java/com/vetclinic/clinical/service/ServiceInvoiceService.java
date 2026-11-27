package com.vetclinic.clinical.service;

import java.util.UUID;
import com.vetclinic.clinical.dto.response.InvoiceStatementResponse;
import com.vetclinic.clinical.dto.response.ServiceInvoiceResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;

import java.math.BigDecimal;
import java.util.List;

/**
 * Hóa đơn dịch vụ (v3.0): nằm cùng CSDL với chỉ định và đợt khám nên "thu tiền + hoàn tất ca" là MỘT giao dịch cục bộ
 * (không còn sự kiện liên service). Không có API hủy hóa đơn lẻ: muốn bỏ một khoản thì hủy dịch vụ (BR-33).
 */
public interface ServiceInvoiceService {

    /** UC-34: bảng kê hóa đơn của một đợt khám, tổng cần thu = tổng hóa đơn PENDING. */
    InvoiceStatementResponse statement(UUID visitId);

    /**
     * EX-14: giảm giá trên MỘT hóa đơn, không vượt quá quantity x unit_price x (1 + vat_rate) của chính hóa đơn đó (BILL_1402). Chỉ khi hóa
     * đơn còn PENDING (BILL_1403 / BILL_1408); dịch vụ làm tại phòng thì ca phải đã chốt hoặc còn công nợ (BILL_1409), dịch vụ thu
     * trước thì giảm được ngay trước khi thu.
     */
    ServiceInvoiceResponse discount(UUID invoiceId, BigDecimal discountAmount);

    /**
     * UC-35 / T10 / BR-35: thu các hóa đơn đã chọn (được thu một phần). Dịch vụ thu trước (services.requires_prepayment: khám, XN,
     * CĐHA) thu được ngay khi đã chỉ định và PHÁT PHIẾU XẾP HÀNG trong cùng giao dịch (TicketIssuer); dịch vụ làm tại phòng chỉ thu khi
     * ca WAITING_PAYMENT hoặc COMPLETED còn công nợ (BILL_1409). Mọi id phải thuộc ca (BILL_1401) và đang PENDING (BILL_1403 đã thu /
     * đã hoàn, BILL_1408 đã hủy). Cuối giao dịch: không còn hóa đơn PENDING và ca đang WAITING_PAYMENT thì ca -> COMPLETED + lịch sử,
     * rồi phát VisitCompletedEvent. Ca COMPLETED do khách bỏ về: chỉ thu công nợ.
     */
    InvoiceStatementResponse confirmPayment(UUID visitId, List<UUID> invoiceIds);

    /** Ba mục của màn hình Thu ngân (BR-35). */
    enum CashierSection { EXAM_FEE, PARACLINIC, FINAL }

    /** Các ca đang chờ thu ở một mục của màn hình Thu ngân, cũ nhất trước. */
    List<VisitResponse> cashierPending(CashierSection section);

    /** Danh sách ca COMPLETED còn hóa đơn PENDING (công nợ). */
    List<VisitResponse> visitsWithDebt();
}
