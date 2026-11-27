package com.vetclinic.clinical.queue;

import java.util.UUID;

/** Phiếu vừa phát. {@code duplicate = true}: khóa chống trùng đã có, trả lại phiếu đã phát trước đó (không tạo phiếu thứ hai). */
public record IssuedTicket(UUID ticketId, Integer ticketNo, boolean duplicate) {
}
