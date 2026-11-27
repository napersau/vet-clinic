package com.vetclinic.billing.constant;

/** Khớp 100% với ENUM inventory_transactions.transaction_type (v3.0: EXPORT_PRESCRIPTION đổi thành EXPORT_SALE). */
public enum InventoryTransactionType {
    IMPORT,
    EXPORT_SALE,
    ADJUSTMENT
}
