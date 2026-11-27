import { useState } from 'react';
import { Link } from 'react-router-dom';
import StaffLayout from '../../layouts/StaffLayout';
import { AsyncView, Badge, Button, Empty, Field, FormDialog, Notice, PageHead, Panel, Select, Stats, Table, Textarea } from '../../components/ui';
import { paraclinicApi, queueTicketsApi } from '../../api/endpoints';
import { useNotificationEvents } from '../../contexts/NotificationContext';
import useAsync from '../../hooks/useAsync';
import useAction from '../../hooks/useAction';
import usePolling from '../../hooks/usePolling';
import { formatTimeOfInstant } from '../../lib/format';
import { LAB_BADGE, QUEUE_STAGE, TICKET_PRIORITY } from '../../lib/labels';

export const ROOM = {
  lab: { area: 'lab', title: 'Hàng đợi xét nghiệm', eyebrow: 'PHÒNG XÉT NGHIỆM', base: '/lab', load: paraclinicApi.labQueue, queueType: 'LABORATORY' },
  imaging: { area: 'imaging', title: 'Hàng đợi chẩn đoán hình ảnh', eyebrow: 'PHÒNG CĐHA', base: '/imaging', load: paraclinicApi.imagingQueue, queueType: 'IMAGING' },
};

/**
 * Hủy một dịch vụ CLS. Ai chịu lỗi quyết định tiền (BR-33): khách tự hủy / bỏ về -> hóa đơn đã thu giữ nguyên (không hoàn);
 * phòng khám không làm được (máy hỏng, không lấy được mẫu) -> hóa đơn đã thu chuyển "Đã hoàn tiền". Chưa thu thì hóa đơn hủy theo.
 */
function CancelDialog({ item, onClose, onSubmit }) {
  const [reason, setReason] = useState('');
  const [reasonType, setReasonType] = useState('CUSTOMER');
  return <FormDialog title={`Hủy dịch vụ — ${item.petName}`} onClose={onClose} danger submitLabel="Hủy dịch vụ"
    onSubmit={async () => { if (!reason.trim()) throw new Error('Nhập lý do.'); await onSubmit(reason.trim(), reasonType); }}>
    <p className="ui-confirm-text">{item.serviceName}</p>
    <Field label="Nguyên nhân *" hint={reasonType === 'CLINIC' ? 'Nếu khách đã nộp tiền dịch vụ này, hóa đơn được hoàn tiền.' : 'Tiền đã nộp không được hoàn.'}>
      <Select value={reasonType} onChange={(event) => setReasonType(event.target.value)}>
        <option value="CUSTOMER">Khách tự hủy / bỏ về</option>
        <option value="CLINIC">Phòng khám không thực hiện được (máy hỏng, không lấy được mẫu…)</option>
      </Select></Field>
    <Field label="Lý do *"><Textarea value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500} rows={2} autoFocus /></Field>
  </FormDialog>;
}

/** Gom các dòng của CÙNG một phiếu xếp hàng (cùng đợt khám, cùng phòng; giữ thứ tự phiếu): một lần gọi, một lần bắt đầu. */
function groupByTicket(items) {
  const map = new Map();
  items.forEach((item) => { if (!map.has(item.ticketId)) map.set(item.ticketId, []); map.get(item.ticketId).push(item); });
  return [...map.values()].map((rows) => ({ key: rows[0].ticketId, head: rows[0], rows }));
}
const serviceNames = (rows) => rows.map((r) => r.serviceName).join(' + ');

/**
 * Hàng đợi Lab / CĐHA (17/11/2026): danh sách PHIẾU XẾP HÀNG của phòng. Phiếu chỉ được phát khi khách đã nộp tiền dịch vụ (ca cấp cứu
 * phát ngay), nên dịch vụ chưa thanh toán không hiện ở đây. Mỗi phiếu có nút "Gọi" (chỉ báo khách, KHÔNG đổi trạng thái và thứ tự) và
 * "Bắt đầu" khi khách vào phòng. Nhiều dịch vụ cùng đợt khám, cùng phòng (vd. siêu âm + X-quang) chung MỘT phiếu; nhập kết quả và hủy theo từng dịch vụ.
 */
export default function QueuePage({ kind }) {
  const room = ROOM[kind];
  const queue = useAsync(() => room.load(), [kind]);
  const [dialog, setDialog] = useState(null);
  const action = useAction();
  useNotificationEvents(() => queue.refresh(), ['PATIENT_CALLED', 'TICKET_ISSUED']);
  usePolling(queue.refresh, 15000);
  const done = (text) => { setDialog(null); action.setNotice({ type: 'success', text }); queue.refresh(); };

  const callNext = async () => {
    const ticket = await action.run(() => queueTicketsApi.callNext(room.queueType));
    if (ticket && ticket !== true) { action.setNotice({ type: 'success', text: `Đã gọi số ${ticket.ticketNo} (${ticket.petName}).` }); queue.refresh(); }
  };
  const call = async (group) => {
    if (await action.run(() => queueTicketsApi.call(group.key), `Đã gọi số ${group.head.ticketNo} (${group.head.petName}) vào phòng.`)) queue.refresh();
  };
  const start = async (group) => {
    if (await action.run(() => queueTicketsApi.start(group.key), `Đã bắt đầu ${serviceNames(group.rows)} cho ${group.head.petName}.`)) queue.refresh();
  };
  const sample = async (item) => { if (await action.run(() => paraclinicApi.sampleCollected(item.serviceDetailId), `Đã ghi nhận lấy mẫu của ${item.petName}.`)) queue.refresh(); };
  const calledLabel = (group) => (group.head.lastCalledAt ? `Đã gọi lúc ${formatTimeOfInstant(group.head.lastCalledAt)}` : null);

  return <StaffLayout area={room.area} title={room.title}>
    <PageHead eyebrow={room.eyebrow} title={room.title} actions={<Button onClick={queue.reload}>Làm mới</Button>}>Chỉ ca đã nộp tiền mới có phiếu. Cấp cứu lên đầu, còn lại theo giờ phát phiếu. Khách vắng thì bấm Gọi phiếu khác (thứ tự không đổi).</PageHead>
    <Notice notice={action.notice} onClose={action.clear} />
    <AsyncView state={queue}>
      {(items) => {
        const workingGroups = groupByTicket(items.filter((item) => item.status === 'IN_PROGRESS'));
        const waitingGroups = groupByTicket(items.filter((item) => item.status !== 'IN_PROGRESS'));
        const urgent = waitingGroups.filter((g) => g.head.emergency);
        return <>
          <Stats items={[['Đang thực hiện', workingGroups.length], ['Đang chờ', waitingGroups.length], ['Cấp cứu', urgent.length, undefined, urgent.length ? 'red' : '']]} />

          {workingGroups.length > 0 && <Panel title="Đang thực hiện" flush>
            <Table caption="Đang thực hiện" rows={workingGroups} rowKey="key" columns={[
              { title: 'Thú cưng', render: (g) => <><strong className="ui-ticket-no">Số {g.head.ticketNo}</strong> <strong>{g.head.petName}</strong><small>{g.head.speciesName} · {g.head.visitCode}</small></> },
              { title: 'Dịch vụ', render: (g) => <div style={{ display: 'grid', gap: 8 }}>{g.rows.map((row) => <div key={row.serviceDetailId} className="ui-actions" style={{ justifyContent: 'space-between' }}>
                <span>{row.serviceName} {row.stage ? <Badge map={QUEUE_STAGE} value={row.stage} /> : <Badge tone="blue">Đang thực hiện</Badge>}</span>
                <span className="ui-actions">
                  {kind === 'lab' && row.stage === 'SAMPLING' && <Button className="small" onClick={() => sample(row)}>Đã lấy mẫu</Button>}
                  <Link className="ui-button small primary" to={`${room.base}/entry/${row.serviceDetailId}`}>Nhập kết quả</Link>
                  <Button className="small danger" onClick={() => setDialog({ item: row })}>Hủy</Button></span></div>)}</div> }]} />
          </Panel>}

          <Panel title="Hàng đợi" subtitle="Bấm Gọi để báo chủ nuôi vào phòng; các dịch vụ cùng phiếu được gọi chung một lượt. Gọi không làm đổi thứ tự." actions={<Button variant="primary" disabled={waitingGroups.length === 0} busy={action.busy} onClick={callNext}>Gọi số tiếp theo</Button>} flush>
            {waitingGroups.length === 0 ? <Empty>Hiện không có ca nào chờ ở phòng này.</Empty> : <Table caption={room.title} rows={waitingGroups} rowKey="key" rowClass={(g) => (g.head.emergency ? 'emergency' : '')} columns={[
              { title: 'Số', render: (g) => <><strong className="ui-ticket-no">{g.head.ticketNo}</strong>{g.head.ticketPriority > 0 && <Badge map={TICKET_PRIORITY} value={g.head.ticketPriority} />}</> },
              { title: 'Thú cưng', render: (g) => <><strong>{g.head.petName}</strong>{g.head.emergency && <span className="ui-emergency"> · CẤP CỨU</span>}{calledLabel(g) && <Badge tone="amber">{calledLabel(g)}</Badge>}<small>{g.head.speciesName} · {g.head.visitCode}</small></> },
              { title: 'Dịch vụ', render: (g) => <div style={{ display: 'grid', gap: 6 }}>{g.rows.map((row) => <div key={row.serviceDetailId} className="ui-actions" style={{ justifyContent: 'space-between' }}>
                <span>{row.serviceName}{row.notes && <small>{row.notes}</small>}</span>
                {g.rows.length > 1 && <Button className="small danger" onClick={() => setDialog({ item: row })}>Hủy dịch vụ</Button>}</div>)}</div> },
              { title: 'Phát phiếu lúc', render: (g) => formatTimeOfInstant(g.head.issuedAt) },
              { title: kind === 'imaging' ? 'Liên phòng' : 'Giai đoạn', render: (g) => (kind === 'imaging'
                ? (g.head.labStatus ? <Badge map={LAB_BADGE} value={g.head.labStatus} /> : <span className="ui-muted">—</span>)
                : <Badge map={QUEUE_STAGE} value={g.head.stage || 'QUEUED'} />) },
              { title: '', align: 'right', render: (g) => <div className="ui-actions" style={{ justifyContent: 'flex-end' }}>
                <Button className="small" onClick={() => call(g)}>{g.head.lastCalledAt ? 'Gọi lại' : 'Gọi'}</Button>
                <Button className="small primary" onClick={() => start(g)}>Bắt đầu</Button>
                {g.rows.length === 1 && <Button className="small danger" onClick={() => setDialog({ item: g.head })}>Hủy</Button>}</div> }]} />}
          </Panel>
          {dialog && <CancelDialog item={dialog.item} onClose={() => setDialog(null)} onSubmit={async (reason, reasonType) => { await paraclinicApi.cancel(dialog.item.serviceDetailId, reason, reasonType); done(`Đã hủy dịch vụ của ${dialog.item.petName}.`); }} />}
        </>;
      }}
    </AsyncView>
  </StaffLayout>;
}
