import { useState } from 'react';
import { Link } from 'react-router-dom';
import StaffLayout from '../../layouts/StaffLayout';
import { AsyncView, Badge, Button, ConfirmDialog, Field, FormDialog, Notice, PageHead, Panel, Stats, Table, Textarea } from '../../components/ui';
import { visitsApi } from '../../api/endpoints';
import { useNotificationEvents } from '../../contexts/NotificationContext';
import useAsync from '../../hooks/useAsync';
import useAction from '../../hooks/useAction';
import usePolling from '../../hooks/usePolling';
import { formatDateTime, formatTimeOfInstant } from '../../lib/format';
import { sameId } from '../../lib/ids';
import { DOCTOR_STATUS, TICKET_PRIORITY, VISIT_STATUS } from '../../lib/labels';

const waitedMinutes = (instant) => Math.max(0, Math.floor((Date.now() - new Date(instant).getTime()) / 60000));
// Ca đang xử lý ngoài hàng đợi bác sĩ: đang khám, chờ kết quả CLS, chờ thanh toán (ca WAITING / RESULTS_READY nằm trong hàng đợi từng bác sĩ)
const ACTIVE = ['IN_PROGRESS', 'WAITING_RESULTS', 'WAITING_PAYMENT'];

async function loadBoard() {
  const [board, ...active] = await Promise.all([visitsApi.queue(), ...ACTIVE.map((status) => visitsApi.list({ status, size: 50 }))]);
  return { board, active: active.flatMap((page) => page.items).sort((a, b) => String(a.checkedInAt).localeCompare(String(b.checkedInAt))) };
}

/** Lễ tân xếp ca cho BẤT KỲ bác sĩ nào, bất cứ lúc nào, không cần bác sĩ rảnh (Q3). Đổi bác sĩ: phiếu đi theo, giữ nguyên số và giờ phát. */
function AssignDialog({ visit, doctors, suggestedDoctorId, onClose, onDone }) {
  const [doctorId, setDoctorId] = useState(visit.doctorId ? '' : String(suggestedDoctorId || ''));
  const change = Boolean(visit.doctorId);
  const submit = async () => {
    if (!doctorId) throw new Error('Chọn bác sĩ phụ trách.');
    await visitsApi.assignDoctor(visit.id, doctorId);
    onDone(`Đã ${change ? 'đổi bác sĩ' : 'phân công bác sĩ'} cho ${visit.petName} (${visit.visitCode}).`);
  };
  return <FormDialog title={`${change ? 'Đổi bác sĩ' : 'Phân công bác sĩ'} — ${visit.petName}`} onClose={onClose} onSubmit={submit} submitLabel={change ? 'Đổi bác sĩ' : 'Phân công'}>
    <p className="ui-confirm-text">Chọn bác sĩ bất kỳ, kể cả bác sĩ đang khám ca khác — ca (khi đã có phiếu) vào hàng đợi riêng của bác sĩ đó{change ? ', phiếu giữ nguyên số và giờ phát' : ''}. Bác sĩ có ít phiếu chờ nhất được gợi ý.</p>
    <Field label="Bác sĩ *"><select className="ui-input" value={doctorId} onChange={(event) => setDoctorId(event.target.value)} autoFocus>
      <option value="">Chọn bác sĩ</option>
      {doctors.map((doctor) => <option key={doctor.doctorId} value={doctor.doctorId} disabled={sameId(doctor.doctorId, visit.doctorId)}>
        {doctor.fullName} — phòng {doctor.roomNumber || '?'} — {doctor.waitingCount} phiếu chờ{sameId(doctor.doctorId, suggestedDoctorId) ? ' — gợi ý' : ''}{sameId(doctor.doctorId, visit.doctorId) ? ' — đang được phân công ca này' : ''}</option>)}
    </select></Field>
  </FormDialog>;
}

function CancelVisitDialog({ visit, onClose, onDone }) {
  const [reason, setReason] = useState('');
  const submit = async () => {
    if (!reason.trim()) throw new Error('Nhập lý do hủy.');
    await visitsApi.cancel(visit.id, reason.trim());
    onDone(`Đã hủy tiếp nhận ${visit.visitCode}.`);
  };
  return <FormDialog title={`Hủy tiếp nhận ${visit.visitCode}`} onClose={onClose} onSubmit={submit} submitLabel="Hủy tiếp nhận" danger>
    <p className="ui-confirm-text">Chỉ hủy được ca đang chờ khám (khách bỏ về khi chưa vào phòng).</p>
    <Field label="Lý do *"><Textarea value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500} autoFocus /></Field>
  </FormDialog>;
}

function HistoryDialog({ visit, onClose }) {
  const history = useAsync(() => visitsApi.history(visit.id), [visit.id]);
  return <FormDialog title={`Lịch sử ${visit.visitCode}`} onClose={onClose} onSubmit={async () => {}} submitLabel="Đóng">
    <AsyncView state={history} empty="Chưa có lịch sử.">{(items) => <ul className="ui-list">{items.map((item) => <li key={item.id}><div><strong>{item.fromStatus ? `${VISIT_STATUS[item.fromStatus]?.label} → ` : ''}{VISIT_STATUS[item.toStatus]?.label}</strong>{item.notes && <small>{item.notes}</small>}</div><small>{formatDateTime(item.createdAt)}</small></li>)}</ul>}</AsyncView>
  </FormDialog>;
}

function VisitCell({ visit }) {
  return <>
    {visit.ticket && <><strong className="ui-ticket-no">Số {visit.ticket.ticketNo}</strong>{' '}</>}
    <strong>{visit.petName}</strong>{visit.emergency && <span className="ui-emergency"> · CẤP CỨU</span>}
    {visit.ticket && visit.ticket.priority === 1 && <Badge map={TICKET_PRIORITY} value={1} />}
    <small>{visit.speciesName} · {visit.visitCode}</small>
  </>;
}

export default function QueuePage() {
  const board = useAsync(loadBoard, []);
  const [dialog, setDialog] = useState(null);
  const action = useAction();
  useNotificationEvents(() => board.refresh(), ['VISIT_CHECKED_IN', 'TICKET_ISSUED', 'PATIENT_CALLED', 'RESULTS_READY', 'VISIT_COMPLETED', 'USER_STATUS_CHANGED']);
  usePolling(board.refresh, 30000);
  const done = (text) => { setDialog(null); action.setNotice({ type: 'success', text }); board.refresh(); };
  const run = (fn, text) => action.run(fn, text).then((result) => { if (result) board.refresh(); });

  return <StaffLayout area="reception" title="Bảng điều phối">
    <PageHead eyebrow="ĐIỀU PHỐI" title="Bảng điều phối hàng đợi" actions={<Button onClick={board.reload}>Làm mới</Button>}>
      Khách nộp phí khám xong mới được phát phiếu và vào hàng đợi bác sĩ (ca cấp cứu vào ngay). Mỗi bác sĩ có hàng đợi phiếu riêng: cấp cứu trước, rồi ca quay lại với kết quả cận lâm sàng, rồi theo giờ phát phiếu. Bạn có thể xếp hoặc chuyển ca cho bác sĩ bất kỳ, bất cứ lúc nào.
    </PageHead>
    <Notice notice={action.notice} onClose={action.clear} />
    <AsyncView state={board}>
      {({ board: d, active }) => {
        const doctorName = (id) => d.doctors.find((doctor) => sameId(doctor.doctorId, id))?.fullName || (id ? `#${id}` : '—');
        // EX-17: Bác sĩ đã bị khóa không còn trong danh sách đang hoạt động; ca của họ vẫn hiện tên (bản chụp) kèm nhãn "Đã khóa"
        const doctorCell = (row) => (row.doctorId
          ? <>{d.doctors.find((doctor) => sameId(doctor.doctorId, row.doctorId))?.fullName || row.doctorName || doctorName(row.doctorId)}{row.doctorActive === false && <> <Badge tone="red">Đã khóa</Badge></>}</>
          : <span className="ui-muted">Chưa chọn</span>);
        const waitingTotal = d.unassigned.length + d.doctors.reduce((sum, doctor) => sum + doctor.waitingCount, 0);
        const emergencies = [...d.unassigned, ...d.doctors.flatMap((doctor) => doctor.visits)].filter((visit) => visit.emergency).length;
        const awaiting = d.awaitingPayment || [];
        return <>
          <Stats items={[['Chờ nộp phí khám', awaiting.length, undefined, awaiting.length ? 'amber' : ''],
            ['Chờ phân công', d.unassigned.length, undefined, d.unassigned.length ? 'amber' : ''],
            ['Tổng ca đang chờ', waitingTotal, `${emergencies} ca cấp cứu`, emergencies ? 'red' : ''],
            ['Đang khám / chờ kết quả', active.filter((v) => ['IN_PROGRESS', 'WAITING_RESULTS'].includes(v.status)).length],
            ['Chờ thanh toán', active.filter((v) => v.status === 'WAITING_PAYMENT').length, undefined, 'amber'],
            ['Bác sĩ đang rảnh', d.doctors.filter((doctor) => doctor.status === 'AVAILABLE').length, `${d.doctors.length} bác sĩ đang hoạt động`, 'green']]} />

          <Panel title="Chờ nộp phí khám" subtitle="Ca vừa tiếp đón, chưa nộp phí khám nên chưa có phiếu và chưa vào hàng đợi bác sĩ. Thu phí xong hệ thống tự phát phiếu." flush>
            <Table caption="Chờ nộp phí khám" empty="Không có ca nào chờ nộp phí khám." rows={awaiting} columns={[
              { title: 'Thú cưng', render: (row) => <VisitCell visit={row} /> },
              { title: 'Giờ đến', render: (row) => <>{formatTimeOfInstant(row.checkedInAt)}<small>chờ {waitedMinutes(row.checkedInAt)} phút</small></> },
              { title: 'Bác sĩ đã chọn', render: (row) => doctorCell(row) },
              { title: '', align: 'right', render: (row) => <div className="ui-actions" style={{ justifyContent: 'flex-end' }}>
                <Link className="ui-button small primary" to={`/reception/invoices/${row.id}`}>Thu phí khám</Link>
                <Button className="small" onClick={() => setDialog({ type: 'assign', visit: row })}>{row.doctorId ? 'Đổi bác sĩ' : 'Chọn bác sĩ'}</Button>
                <Button className="small" onClick={() => setDialog({ type: 'cancel', visit: row })}>Hủy</Button></div> }]} />
          </Panel>

          <Panel title="Danh sách chờ phân công" subtitle="Ca đã nộp phí (có phiếu) nhưng chưa có bác sĩ. Bác sĩ có ít phiếu chờ nhất được gợi ý nhưng bạn quyết định." flush>
            <Table caption="Danh sách chờ phân công" empty="Không có ca nào đang chờ phân công." rows={d.unassigned} rowClass={(row) => (row.emergency ? 'emergency' : '')} columns={[
              { title: 'Thú cưng', render: (row) => <VisitCell visit={row} /> },
              { title: 'Phát phiếu', render: (row) => <>{formatTimeOfInstant(row.ticket?.issuedAt || row.checkedInAt)}<small>chờ {waitedMinutes(row.ticket?.issuedAt || row.checkedInAt)} phút</small></> },
              { title: 'Lý do đến khám', render: (row) => row.reasonForVisit || '—' },
              { title: '', align: 'right', render: (row) => <div className="ui-actions" style={{ justifyContent: 'flex-end' }}>
                <Button className="small primary" onClick={() => setDialog({ type: 'assign', visit: row })}>Phân công{d.suggestedDoctorId ? ` (gợi ý: ${doctorName(d.suggestedDoctorId)})` : ''}</Button>
                <Button className="small" onClick={() => setDialog({ type: 'cancel', visit: row })}>Hủy</Button></div> }]} />
          </Panel>

          <div className="ui-cols">
            {d.doctors.length === 0 && <Panel title="Hàng đợi bác sĩ"><p className="ui-muted">Chưa có bác sĩ nào đang hoạt động.</p></Panel>}
            {d.doctors.map((doctor) => <Panel key={doctor.doctorId} title={`${doctor.fullName} — phòng ${doctor.roomNumber || '?'}`} subtitle={`${doctor.waitingCount} phiếu đang chờ`} actions={<Badge map={DOCTOR_STATUS} value={doctor.status} />} flush>
              <Table caption={`Hàng đợi của ${doctor.fullName}`} empty="Hàng đợi trống." rows={doctor.visits} rowClass={(row) => (row.emergency ? 'emergency' : '')} columns={[
                { title: 'Thú cưng', render: (row) => <VisitCell visit={row} /> },
                { title: 'Trạng thái', render: (row) => <><Badge map={VISIT_STATUS} value={row.status} /><small>phiếu lúc {formatTimeOfInstant(row.ticket?.issuedAt || row.checkedInAt)}{row.ticket?.lastCalledAt ? ` · đã gọi ${formatTimeOfInstant(row.ticket.lastCalledAt)}` : ''}</small></> },
                { title: '', align: 'right', render: (row) => <div className="ui-actions" style={{ justifyContent: 'flex-end' }}>
                  {row.status === 'WAITING' && <>
                    <Button className="small" onClick={() => setDialog({ type: 'assign', visit: row })}>Đổi bác sĩ</Button>
                    <Button className="small" onClick={() => run(() => visitsApi.unassignDoctor(row.id), `Đã gỡ phân công ${row.petName}; ca về danh sách chờ phân công.`)}>Gỡ</Button>
                    <Button className="small" onClick={() => setDialog({ type: 'cancel', visit: row })}>Hủy</Button></>}
                  {row.status === 'RESULTS_READY' && <Button className="small" onClick={() => setDialog({ type: 'abandon', visit: row })}>Khách bỏ về</Button>}
                </div> }]} />
            </Panel>)}
          </div>

          <Panel title="Các ca đang xử lý" subtitle="Ca đang khám, chờ kết quả cận lâm sàng, chờ thanh toán." flush>
            <Table caption="Các ca đang xử lý" empty="Không có ca nào đang xử lý." rows={active} columns={[
              { title: 'Mã ca', render: (row) => <><strong>{row.visitCode}</strong><small>{formatDateTime(row.checkedInAt)}</small></> },
              { title: 'Thú cưng', render: (row) => <>{row.petName}{row.emergency && <span className="ui-emergency"> · CẤP CỨU</span>}<small>{row.speciesName}</small></> },
              { title: 'Bác sĩ', render: (row) => (row.doctorId ? doctorCell(row) : '—') },
              { title: 'Trạng thái', render: (row) => <Badge map={VISIT_STATUS} value={row.status} /> },
              { title: '', align: 'right', render: (row) => <div className="ui-actions" style={{ justifyContent: 'flex-end' }}>
                {row.status === 'WAITING_PAYMENT' && <Link className="ui-button small primary" to={`/reception/invoices/${row.id}`}>Thu tiền</Link>}
                {row.status === 'WAITING_RESULTS' && <><Link className="ui-button small" to={`/reception/invoices/${row.id}`}>Thu tiền XN / CĐHA</Link>
                  <Button className="small" onClick={() => setDialog({ type: 'abandon', visit: row })}>Khách bỏ về</Button></>}
                <Button className="small" onClick={() => setDialog({ type: 'history', visit: row })}>Lịch sử</Button></div> },
            ]} />
          </Panel>

          {dialog?.type === 'assign' && <AssignDialog visit={dialog.visit} doctors={d.doctors} suggestedDoctorId={d.suggestedDoctorId} onClose={() => setDialog(null)} onDone={done} />}
          {dialog?.type === 'cancel' && <CancelVisitDialog visit={dialog.visit} onClose={() => setDialog(null)} onDone={done} />}
          {dialog?.type === 'history' && <HistoryDialog visit={dialog.visit} onClose={() => setDialog(null)} />}
          {dialog?.type === 'abandon' && <ConfirmDialog title="Khách bỏ về" onClose={() => setDialog(null)} confirmLabel="Xác nhận" danger onConfirm={async () => { await visitsApi.abandon(dialog.visit.id); done(`Đã ghi nhận ${dialog.visit.petName} bỏ về. Ca kết thúc; hóa đơn dịch vụ chưa thu được giữ làm công nợ.`); }}>
            Ca {dialog.visit.visitCode} sẽ kết thúc ngay. Các dịch vụ đã làm vẫn còn hóa đơn chờ thanh toán (công nợ, thu được ở mục Hóa đơn &amp; thanh toán); dịch vụ chưa làm bị hủy kèm hóa đơn.</ConfirmDialog>}
        </>;
      }}
    </AsyncView>
  </StaffLayout>;
}
