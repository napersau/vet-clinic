import { useEffect, useState } from 'react';
import Modal from '../common/Modal';
import { PrescriptionView } from './MedicalEntry';
import { AsyncView, Badge, Button, ConfirmDialog, Facts, Field, Input, Notice, Panel, Table } from '../ui';
import { billingApi, visitsApi } from '../../api/endpoints';
import useAction from '../../hooks/useAction';
import useAsync from '../../hooks/useAsync';
import { formatDateTime, formatMoney } from '../../lib/format';
import { PAYMENT_STATUS } from '../../lib/labels';

const CLOSED = ['WAITING_PAYMENT', 'COMPLETED'];

/** Tồn kho của một dòng thuốc kèm cảnh báo: hết hàng / còn dưới mức tối thiểu / số mua vượt tồn. */
function StockCell({ stock, minStock, quantity }) {
  const qty = Number(quantity);
  const over = Number.isFinite(qty) && qty > stock;
  const remaining = Number.isFinite(qty) && qty >= 0 ? stock - qty : stock;
  return <>
    <strong style={{ color: stock === 0 ? 'var(--ui-danger, #f87171)' : undefined }}>{stock === 0 ? 'Hết hàng' : stock}</strong>
    {over && <small style={{ color: 'var(--ui-danger, #f87171)' }}>Vượt tồn kho (tối đa {stock})</small>}
    {!over && stock > 0 && remaining < minStock && <small style={{ color: 'var(--ui-warning, #fbbf24)' }}>Sau khi bán còn {remaining}, dưới mức tối thiểu {minStock}</small>}
  </>;
}

/** In đơn cho khách mua thuốc ngoài (BR-32): không tạo hóa đơn, không đổi gì trong hệ thống. */
function PrintDialog({ visitId, onClose }) {
  const printable = useAsync(() => visitsApi.printPrescription(visitId), [visitId]);
  return <Modal title="Đơn thuốc" id="print-prescription" onClose={onClose}>
    <AsyncView state={printable}>{(p) => <div className="ui-stack" style={{ gap: 12 }}>
      <p className="ui-confirm-text">{p.visitCode} · {p.petName} · Chủ nuôi {p.ownerName} · Bác sĩ {p.doctorName} · Kê lúc {formatDateTime(p.createdAt)}</p>
      <PrescriptionView prescription={p} />
      <div className="ui-actions ui-no-print"><Button variant="primary" onClick={() => window.print()}>In đơn</Button><Button onClick={onClose}>Đóng</Button></div>
    </div>}</AsyncView>
  </Modal>;
}

/**
 * Hóa đơn thuốc (nháp tự tạo khi Bác sĩ chốt ca): sửa số lượng khi còn chờ (xử lý thiếu kho), bỏ dòng (số lượng 0), giảm giá, thu tiền + trừ kho,
 * hoặc hủy khi khách mua ngoài. Dòng thuốc được định danh theo thuốc (medicineId).
 */
function InvoiceEditor({ invoice, onChanged }) {
  const [quantities, setQuantities] = useState({});
  const [discount, setDiscount] = useState('');
  const [dialog, setDialog] = useState(null);
  const action = useAction();
  useEffect(() => {
    setQuantities(Object.fromEntries(invoice.items.map((item) => [item.medicineId, String(item.quantity)])));
    setDiscount(String(invoice.discountAmount ?? 0));
  }, [invoice]);

  const save = async () => {
    const items = invoice.items.map((item) => ({ medicineId: item.medicineId, quantity: Number(quantities[item.medicineId]) }));
    if (items.some((line) => !Number.isInteger(line.quantity) || line.quantity < 0)) { action.setNotice({ type: 'error', text: 'Số lượng phải là số nguyên không âm (0 = bỏ dòng khỏi hóa đơn).' }); return; }
    const tooMany = invoice.items.find((item) => Number(quantities[item.medicineId]) > item.stockQuantity);
    if (tooMany) { action.setNotice({ type: 'error', text: `Thuốc ${tooMany.medicineName} chỉ còn ${tooMany.stockQuantity} ${tooMany.unit} trong kho, không bán quá số này.` }); return; }
    if (await action.run(() => billingApi.updateMedicineInvoiceItems(invoice.id, { items, discountAmount: Number(discount) || 0 }), 'Đã cập nhật hóa đơn thuốc.')) onChanged();
  };

  const pending = invoice.paymentStatus === 'PENDING';
  return <div className="ui-stack" style={{ gap: 14 }}>
    <Notice notice={action.notice} onClose={action.clear} />
    <Facts items={[['Mã hóa đơn thuốc', invoice.invoiceCode], ['Trạng thái', <Badge key="s" map={PAYMENT_STATUS} value={invoice.paymentStatus} />], ['Trước VAT', formatMoney(invoice.amountBeforeVat)], ['VAT', formatMoney(invoice.vatAmount)], ['Giảm giá', formatMoney(invoice.discountAmount)],
      ['Phải trả', <strong key="p">{formatMoney(invoice.payableAmount)}</strong>], ['Thu lúc', formatDateTime(invoice.paidAt)], ['Thu ngân', invoice.cashierId ? `#${invoice.cashierId}` : null]]} />
    <Table caption="Thuốc trong hóa đơn" rowKey="id" rows={invoice.items} columns={[
      { title: 'Thuốc', render: (row) => <><strong>{row.medicineName}</strong><small>{row.medicineCode}</small></> },
      { title: 'Số lượng mua', render: (row) => (pending
        ? <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}><Input type="number" min="0" max={row.stockQuantity} aria-label={`Số lượng ${row.medicineName}`} style={{ maxWidth: 90 }} value={quantities[row.medicineId] ?? ''} onChange={(event) => setQuantities({ ...quantities, [row.medicineId]: event.target.value })} /><span className="ui-muted">{row.unit} · bác sĩ kê {row.prescribedQuantity}</span></div>
        : `${row.quantity} ${row.unit} (bác sĩ kê ${row.prescribedQuantity})`) },
      ...(pending ? [{ title: 'Tồn kho', render: (row) => <StockCell stock={row.stockQuantity} minStock={row.minStockAlert} quantity={quantities[row.medicineId]} /> }] : []),
      { title: pending ? 'Đơn giá (cập nhật lúc thu)' : 'Đơn giá (đã chốt)', align: 'right', render: (row) => <>{formatMoney(row.unitPrice)}<small>VAT {Math.round(Number(row.vatRate) * 100)}%</small></> },
      { title: 'Thành tiền', align: 'right', render: (row) => formatMoney(Number(row.amountBeforeVat) + Number(row.vatAmount)) }]} />
    {pending && <>
      <Field label="Giảm giá (VND)" hint="Không vượt quá tổng tiền thuốc của hóa đơn này."><Input type="number" min="0" step="1000" style={{ maxWidth: 200 }} value={discount} onChange={(event) => setDiscount(event.target.value)} /></Field>
      <p className="ui-muted" style={{ margin: 0 }}>Hóa đơn nháp tự tạo từ đơn thuốc khi Bác sĩ chốt ca. Số lượng bác sĩ kê chỉ để tham khảo: khách có thể mua nhiều hơn, ít hơn hoặc bằng, nhưng không quá tồn kho. Khách mua ngoài: hủy hóa đơn (và in đơn). Giá được cập nhật theo bảng giá hiện hành lúc thu tiền. Nếu kho vừa thay đổi và không đủ: sửa số lượng (0 để bỏ dòng) rồi thu lại.</p>
      <div className="ui-actions ui-no-print">
        <Button variant="primary" onClick={() => setDialog('pay')}>Thu tiền thuốc</Button>
        <Button busy={action.busy} onClick={save}>Lưu thay đổi</Button>
        <Button variant="danger" onClick={() => setDialog('cancel')}>Khách mua ngoài — hủy hóa đơn</Button>
      </div></>}
    {invoice.paymentStatus === 'CANCELLED' && <p className="ui-muted">Hóa đơn đã hủy (khách mua ngoài). Hủy là kết thúc: đơn này không lập lại hóa đơn thuốc; khách mua ngoài bằng đơn in.</p>}
    {dialog === 'pay' && <ConfirmDialog title="Thu tiền thuốc" confirmLabel="Đã thu tiền" onClose={() => setDialog(null)} onConfirm={async () => { await billingApi.confirmMedicinePayment(invoice.id); action.setNotice({ type: 'success', text: 'Đã thu tiền thuốc và trừ kho.' }); onChanged(); }}>
      Thu <strong>{formatMoney(invoice.payableAmount)}</strong> (theo giá hiện hành lúc thu) cho hóa đơn {invoice.invoiceCode}. Kho bị trừ ngay trong cùng thao tác và không hoàn tác được.</ConfirmDialog>}
    {dialog === 'cancel' && <ConfirmDialog title="Hủy hóa đơn thuốc" danger confirmLabel="Hủy hóa đơn" onClose={() => setDialog(null)} onConfirm={async () => { await billingApi.cancelMedicineInvoice(invoice.id); action.setNotice({ type: 'success', text: 'Đã hủy hóa đơn thuốc (kho không đổi).' }); onChanged(); }}>
      Khách mua thuốc ngoài phòng khám. Kho không thay đổi. Đơn thuốc này sẽ không lập lại được hóa đơn thuốc.</ConfirmDialog>}
  </div>;
}

/**
 * Thuốc theo đơn của một đợt khám (UC-36). Hóa đơn thuốc là NHÁP do billing tự tạo khi Bác sĩ chốt ca (có thể trễ khoảng một giây); Thu ngân chỉ
 * sửa / thu / hủy, không còn "lập hóa đơn". Độc lập với hóa đơn dịch vụ và không đổi trạng thái đợt khám.
 */
export default function MedicineSale({ visitId, visitStatus }) {
  const prescription = useAsync(() => visitsApi.prescription(visitId).catch((problem) => { if (problem.status === 404) return null; throw problem; }), [visitId]);
  const invoices = useAsync(() => billingApi.medicineInvoicesByVisit(visitId), [visitId]);
  const [printing, setPrinting] = useState(false);
  const closed = CLOSED.includes(visitStatus);
  const hasPrescription = Boolean(prescription.data);
  const missingDraft = closed && hasPrescription && Array.isArray(invoices.data) && invoices.data.length === 0;

  // Sự kiện chốt ca đi qua hàng đợi nên nháp có thể tới sau vài trăm ms: tải lại đều cho tới khi thấy hóa đơn
  useEffect(() => {
    if (!missingDraft) return undefined;
    const timer = setInterval(() => invoices.refresh(), 2000);
    return () => clearInterval(timer);
  }, [missingDraft, invoices]);

  const invoice = invoices.data?.[0];
  return <Panel title="Thuốc theo đơn" subtitle="Hóa đơn thuốc nháp tự tạo khi Bác sĩ chốt ca. Khách mua tại phòng khám: sửa số lượng và thu tiền; mua ngoài: hủy hóa đơn, in đơn cho khách.">
    <AsyncView state={prescription} isEmpty={() => false}>{(p) => (p === null
      ? <p className="ui-muted">Bác sĩ không kê đơn thuốc cho đợt khám này.</p>
      : <div className="ui-stack" style={{ gap: 14 }}>
        <PrescriptionView prescription={p} />
        <div className="ui-actions ui-no-print"><Button onClick={() => setPrinting(true)}>In đơn cho khách mua ngoài</Button></div>
        {invoice
          ? <InvoiceEditor invoice={invoice} onChanged={() => { invoices.refresh(); prescription.refresh(); }} />
          : <p className="ui-muted">{closed ? 'Đang tạo hóa đơn thuốc nháp từ đơn…' : 'Bác sĩ chưa chốt ca: hóa đơn thuốc nháp sẽ tự xuất hiện sau khi chốt.'}</p>}
        {printing && <PrintDialog visitId={visitId} onClose={() => setPrinting(false)} />}
      </div>)}</AsyncView>
  </Panel>;
}
