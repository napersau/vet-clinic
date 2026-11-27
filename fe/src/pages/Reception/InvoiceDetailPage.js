import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import StaffLayout from '../../layouts/StaffLayout';
import MedicineSale from '../../components/shared/MedicineSale';
import { AsyncView, Badge, Button, ConfirmDialog, Facts, Field, FormDialog, Input, Notice, PageHead, Panel, Table } from '../../components/ui';
import { visitsApi } from '../../api/endpoints';
import useAsync from '../../hooks/useAsync';
import useAction from '../../hooks/useAction';
import { formatDateTime, formatMoney } from '../../lib/format';
import { PAYMENT_STATUS, QUEUE_TYPE, serviceCategoryLabel, TICKET_PRIORITY, VISIT_STATUS } from '../../lib/labels';

/** Giảm giá trên TỪNG hóa đơn dịch vụ, tối đa bằng giá trị (đã gồm VAT) của chính hóa đơn đó (BILL_1402). */
function DiscountDialog({ invoice, onClose, onDone }) {
  const [amount, setAmount] = useState(String(invoice.discountAmount ?? 0));
  const gross = Number(invoice.payableAmount) + Number(invoice.discountAmount);
  const submit = async () => {
    const value = Number(amount);
    if (Number.isNaN(value) || value < 0) throw new Error('Số tiền giảm giá không hợp lệ.');
    if (value > gross) throw new Error(`Giảm giá không được vượt giá trị hóa đơn này (${formatMoney(gross)}).`);
    await visitsApi.discountServiceInvoice(invoice.id, value);
    onDone(`Đã cập nhật giảm giá cho ${invoice.serviceName}.`);
  };
  return <FormDialog title={`Giảm giá — ${invoice.serviceName}`} onClose={onClose} onSubmit={submit}>
    <p className="ui-confirm-text">Giá trị hóa đơn này (đã gồm VAT): <strong>{formatMoney(gross)}</strong>. Giảm giá tối đa bằng số này.</p>
    <Field label="Số tiền giảm giá (VND)"><Input type="number" min="0" step="1000" value={amount} onChange={(event) => setAmount(event.target.value)} autoFocus /></Field>
  </FormDialog>;
}

export default function InvoiceDetailPage() {
  const { invoiceId: visitId } = useParams();
  const visit = useAsync(() => visitsApi.get(visitId), [visitId]);
  const statement = useAsync(() => visitsApi.serviceInvoices(visitId), [visitId]);
  const [selected, setSelected] = useState({});
  const [dialog, setDialog] = useState(null);
  const [issuing, setIssuing] = useState(null);   // { before: số phiếu đã có lúc thu, until: hạn chờ }: phiếu phát SAU khi thu tiền (qua sự kiện, thường dưới 1 giây)
  const action = useAction();
  const data = statement.data;

  // Hàng đợi chạy trên Redis: thu tiền chỉ ghi hóa đơn + sự kiện, phiếu xuất hiện ngay sau đó. Tải lại bảng kê cho tới khi thấy phiếu mới (tối đa 20 giây).
  useEffect(() => {
    if (!issuing) return undefined;
    if ((data?.tickets?.length || 0) > issuing.before || Date.now() > issuing.until) { setIssuing(null); return undefined; }
    const timer = setInterval(() => statement.refresh(), 1500);
    return () => clearInterval(timer);
  }, [issuing, data, statement]);

  // Mặc định chọn mọi hóa đơn đang chờ thu; giữ lựa chọn của thu ngân khi dữ liệu làm mới
  useEffect(() => {
    if (!data) return;
    setSelected((current) => Object.fromEntries(data.invoices.filter((invoice) => invoice.paymentStatus === 'PENDING').map((invoice) => [invoice.id, current[invoice.id] ?? true])));
  }, [data]);

  const reloadAll = () => { visit.refresh(); statement.refresh(); };
  const done = (text) => { setDialog(null); action.setNotice({ type: 'success', text }); reloadAll(); };

  return <StaffLayout area="reception" title="Thu tiền">
    <AsyncView state={statement}>
      {(st) => {
        const status = st.visitStatus;
        const closed = status === 'WAITING_PAYMENT' || status === 'COMPLETED';
        // BR-35: dịch vụ thu trước (khám, XN, CĐHA) thu được ngay; dịch vụ làm tại phòng chỉ thu khi bác sĩ đã chốt ca
        const collectable = (invoice) => invoice.paymentStatus === 'PENDING' && status !== 'CANCELLED' && (invoice.requiresPrepayment || closed);
        const pending = st.invoices.filter((invoice) => invoice.paymentStatus === 'PENDING');
        const chosenTotal = pending.filter((invoice) => selected[invoice.id] && collectable(invoice)).reduce((sum, invoice) => sum + Number(invoice.payableAmount), 0);
        const chosen = pending.filter((invoice) => selected[invoice.id] && collectable(invoice)).map((invoice) => invoice.id);
        const waitingInRoom = pending.filter((invoice) => !collectable(invoice));
        return <>
          <PageHead eyebrow="THU NGÂN" title={`Đợt khám ${st.visitCode}`} actions={<><Link className="ui-button" to="/reception/invoices">← Danh sách</Link><Button onClick={() => window.print()}>In bảng kê</Button></>}>
            {visit.data ? `${visit.data.petName} (${visit.data.speciesName})` : ''}{visit.data?.checkedInAt ? ` · Tiếp nhận ${formatDateTime(visit.data.checkedInAt)}` : ''}
          </PageHead>
          <Notice notice={action.notice} onClose={action.clear} />
          {issuing && <div className="ui-alert" role="status"><span>Đã thu tiền. Đang cấp số thứ tự…</span></div>}
          {st.tickets?.length > 0 && <Panel title="Phiếu xếp hàng của ca" subtitle="In / đọc số cho khách. Khách chờ gọi số tại phòng tương ứng.">
            <div className="ui-actions">{st.tickets.map((ticket) => <div key={ticket.id} className="ui-ticket-card">
              <small>{QUEUE_TYPE[ticket.queueType]}</small><strong>Số {ticket.ticketNo}</strong>
              {ticket.priority > 0 && <Badge map={TICKET_PRIORITY} value={ticket.priority} />}</div>)}</div>
          </Panel>}
          <Panel title="Hóa đơn dịch vụ" subtitle="Mỗi dịch vụ một hóa đơn. Khám, XN, CĐHA thu trước (thu xong phát phiếu); dịch vụ tại phòng thu khi chốt ca." actions={<Badge map={VISIT_STATUS} value={status} />} flush>
            <Table caption="Hóa đơn dịch vụ" rows={st.invoices} columns={[
              { title: '', render: (row) => (row.paymentStatus === 'PENDING' ? <input type="checkbox" aria-label={`Chọn ${row.serviceName}`} disabled={!collectable(row)} checked={collectable(row) && Boolean(selected[row.id])} onChange={() => setSelected({ ...selected, [row.id]: !selected[row.id] })} /> : null) },
              { title: 'Dịch vụ', render: (row) => <><strong>{row.serviceName}</strong><small>{serviceCategoryLabel(row.category)} · {row.invoiceCode} · {row.requiresPrepayment ? 'thu trước' : 'thu khi chốt ca'}</small></> },
              { title: 'SL', key: 'quantity' },
              { title: 'Đơn giá', align: 'right', render: (row) => <>{formatMoney(row.unitPrice)}<small>VAT {Math.round(Number(row.vatRate) * 100)}%</small></> },
              { title: 'Giảm giá', align: 'right', render: (row) => formatMoney(row.discountAmount) },
              { title: 'Phải trả', align: 'right', render: (row) => <strong>{formatMoney(row.payableAmount)}</strong> },
              { title: 'Trạng thái', render: (row) => <><Badge map={PAYMENT_STATUS} value={row.paymentStatus} />{row.paidAt && <small>{formatDateTime(row.paidAt)}</small>}</> },
              { title: '', align: 'right', render: (row) => (collectable(row) ? <Button className="small" onClick={() => setDialog({ type: 'discount', invoice: row })}>Giảm giá</Button> : null) }]} />
          </Panel>
          <Panel title="Tổng kết">
            <Facts items={[['Còn phải thu', <strong key="a">{formatMoney(st.totalPending)}</strong>], ['Đã thu', formatMoney(st.totalPaid)],
              ...(Number(st.totalRefunded) > 0 ? [['Đã hoàn tiền', formatMoney(st.totalRefunded)]] : []), ['Đang chọn thu', formatMoney(chosenTotal)]]} />
            {status === 'COMPLETED' && pending.length > 0 && <div className="ui-alert warning" role="status" style={{ marginTop: 12 }}><span>Khách đã bỏ về: các hóa đơn còn lại là công nợ. Thu bù tại đây; trạng thái ca giữ nguyên là Hoàn tất.</span></div>}
            {!closed && waitingInRoom.length > 0 && status !== 'CANCELLED' && <div className="ui-alert warning" role="status" style={{ marginTop: 12 }}><span>Dịch vụ làm tại phòng ({waitingInRoom.map((invoice) => invoice.serviceName).join(', ')}) thu khi bác sĩ chốt ca.</span></div>}
            <div className="ui-actions ui-no-print" style={{ marginTop: 14 }}>
              {status === 'WAITING_PAYMENT' && pending.length === 0
                ? <Button variant="primary" onClick={() => setDialog({ type: 'finish' })}>Xác nhận hoàn tất ca (không thu thêm)</Button>
                : <Button variant="primary" disabled={chosen.length === 0} onClick={() => setDialog({ type: 'pay' })}>Thu {chosen.length} hóa đơn đã chọn</Button>}
            </div>
            <p className="ui-muted ui-no-print" style={{ marginBottom: 0 }}>Muốn bỏ một khoản: hủy dịch vụ tương ứng (hóa đơn chưa thu tự hủy theo). Phí khám đã nộp không hoàn; tiền XN / CĐHA chỉ hoàn khi phòng khám không thực hiện được.</p>
          </Panel>

          <MedicineSale visitId={visitId} visitStatus={status} />

          {dialog?.type === 'discount' && <DiscountDialog invoice={dialog.invoice} onClose={() => setDialog(null)} onDone={done} />}
          {dialog?.type === 'finish' && <ConfirmDialog title="Hoàn tất ca" confirmLabel="Hoàn tất" onClose={() => setDialog(null)} onConfirm={async () => {
            await visitsApi.confirmServicePayment(visitId, []);
            done('Ca khám đã hoàn tất (các khoản đã thu trước, không còn hóa đơn dịch vụ chờ thu).');
          }}>Mọi hóa đơn dịch vụ của ca đã thu trước (hoặc đã hủy / hoàn tiền). Xác nhận để hoàn tất ca; tiền thuốc (nếu khách mua) thu riêng ở mục bên dưới.</ConfirmDialog>}
          {dialog?.type === 'pay' && <ConfirmDialog title="Xác nhận thu tiền" confirmLabel="Đã thu tiền" onClose={() => setDialog(null)} onConfirm={async () => {
            const before = new Set((st.tickets || []).map((ticket) => ticket.id));
            const result = await visitsApi.confirmServicePayment(visitId, chosen);
            const issued = (result?.tickets || []).filter((ticket) => !before.has(ticket.id));
            // Phiếu phát sau khi thu tiền (sự kiện + Redis): chưa thấy phiếu mới thì hiển thị "Đang cấp số thứ tự…" và tải lại tới khi có
            const expectsTicket = pending.some((invoice) => chosen.includes(invoice.id) && invoice.requiresPrepayment);
            if (expectsTicket && !issued.length) setIssuing({ before: before.size, until: Date.now() + 20000 });
            done(result?.visitStatus === 'COMPLETED' && status === 'WAITING_PAYMENT' ? 'Đã thu tiền. Mọi hóa đơn dịch vụ đã xử lý xong — ca khám hoàn tất.'
              : issued.length ? `Đã thu tiền và phát phiếu: ${issued.map((ticket) => `${QUEUE_TYPE[ticket.queueType]} số ${ticket.ticketNo}`).join(', ')}.`
                : expectsTicket ? 'Đã thu tiền. Hệ thống đang cấp số thứ tự…' : 'Đã ghi nhận thanh toán.');
          }}>
            Thu <strong>{formatMoney(chosenTotal)}</strong> cho {chosen.length} hóa đơn dịch vụ. Không thể hoàn tác sau khi xác nhận.</ConfirmDialog>}
        </>;
      }}
    </AsyncView>
  </StaffLayout>;
}
