import { Link, useNavigate } from 'react-router-dom';
import StaffLayout from '../../layouts/StaffLayout';
import { AsyncView, Badge, Button, Empty, Notice, PageHead, Panel, Stats } from '../../components/ui';
import { queueTicketsApi, visitsApi } from '../../api/endpoints';
import { useNotificationEvents } from '../../contexts/NotificationContext';
import useAsync from '../../hooks/useAsync';
import useAction from '../../hooks/useAction';
import usePolling from '../../hooks/usePolling';
import { formatTimeOfInstant } from '../../lib/format';
import { TICKET_PRIORITY, VISIT_STATUS } from '../../lib/labels';

async function load() {
  const [queue, mine] = await Promise.all([visitsApi.myQueue(), visitsApi.myVisits()]);
  return { queue, inProgress: mine.filter((visit) => visit.status === 'IN_PROGRESS') };
}

function VisitLine({ visit, highlight, children }) {
  return <li style={highlight ? { background: 'var(--ui-raised)', borderRadius: 8 } : undefined}>
    <div>{visit.ticket && <strong className="ui-ticket-no">Số {visit.ticket.ticketNo}</strong>}{' '}<strong>{visit.petName}</strong> <small style={{ display: 'inline' }}>({visit.speciesName})</small>{' '}
      {visit.emergency && <span className="ui-emergency">CẤP CỨU</span>}
      <small>{visit.visitCode} · tiếp nhận {formatTimeOfInstant(visit.checkedInAt)}{visit.reasonForVisit ? ` · ${visit.reasonForVisit}` : ''}</small></div>
    <div className="ui-actions"><Badge map={VISIT_STATUS} value={visit.status} />{children}</div>
  </li>;
}

/**
 * Hàng đợi riêng của bác sĩ (17/11/2026): danh sách PHIẾU XẾP HÀNG DOCTOR đang chờ (chỉ ca đã nộp phí khám, ca cấp cứu, ca quay lại với
 * kết quả CLS). Mỗi phiếu có nút "Gọi" (chỉ báo khách, KHÔNG đổi trạng thái và thứ tự; giờ gọi lưu ở phiếu) và "Bắt đầu khám"
 * ("Bắt đầu giai đoạn 2" với phiếu ưu tiên của ca có kết quả). Khách vắng thì gọi phiếu khác; khách quay lại thì gọi lại.
 */
export default function TodayPage() {
  const board = useAsync(load, []);
  const action = useAction();
  const navigate = useNavigate();
  useNotificationEvents(() => board.refresh(), ['TICKET_ISSUED', 'RESULTS_READY', 'PATIENT_CALLED']);
  usePolling(board.refresh, 20000);

  const callNext = async () => {
    const ticket = await action.run(() => queueTicketsApi.callNext('DOCTOR'));
    if (ticket && ticket !== true) { action.setNotice({ type: 'success', text: `Đã gọi số ${ticket.ticketNo} (${ticket.petName}).` }); board.refresh(); }
  };
  const call = async (visit) => {
    if (await action.run(() => queueTicketsApi.call(visit.ticket.id), `Đã gọi số ${visit.ticket.ticketNo} (${visit.petName}) vào phòng.`)) board.refresh();
  };
  const start = async (visit) => {
    if (visit.status === 'RESULTS_READY') {
      if (!(await action.run(() => visitsApi.startPhase2(visit.id)))) return;
    }
    navigate(`/vet/visits/${visit.id}`);
  };
  const calledLabel = (visit) => (visit.ticket?.lastCalledAt ? `Đã gọi lúc ${formatTimeOfInstant(visit.ticket.lastCalledAt)}` : null);

  return <StaffLayout area="vet" title="Hàng đợi của tôi">
    <PageHead eyebrow="BÁC SĨ" title="Hàng đợi của tôi" actions={<Button onClick={board.reload}>Làm mới</Button>}>
      Chỉ ca đã nộp phí khám mới có phiếu và vào hàng đợi. Cấp cứu trước, rồi ca quay lại với kết quả cận lâm sàng, rồi theo giờ phát phiếu. Khách vắng thì bấm Gọi phiếu khác; thứ tự không đổi.
    </PageHead>
    <Notice notice={action.notice} onClose={action.clear} />
    <AsyncView state={board}>
      {({ queue, inProgress }) => {
        const ready = queue.filter((visit) => visit.status === 'RESULTS_READY').length;
        const busy = inProgress.length > 0;
        return <>
          <Stats items={[['Đang khám', inProgress.length], ['Đang chờ', queue.length], ['Đã có kết quả', ready, ready ? 'Đứng đầu hàng đợi' : undefined, ready ? 'amber' : '']]} />

          {busy && <Panel title="Đang khám" subtitle="Hoàn tất hoặc chốt ca đang khám trước khi gọi bệnh nhân khác.">
            <ul className="ui-list">{inProgress.map((visit) => <VisitLine key={visit.id} visit={visit}><Link className="ui-button small primary" to={`/vet/visits/${visit.id}`}>Mở hồ sơ</Link></VisitLine>)}</ul>
          </Panel>}

          <Panel title="Hàng đợi" subtitle="Bấm Gọi để báo chủ nuôi vào phòng; bệnh nhân vào phòng thì bấm Bắt đầu khám. Gọi không làm đổi thứ tự."
            actions={<Button variant="primary" disabled={busy || queue.length === 0} busy={action.busy} onClick={callNext}>Gọi số tiếp theo</Button>}>
            {queue.length === 0 ? <Empty title="Hàng đợi trống">Khách nộp phí khám xong (hoặc ca cấp cứu) thì phiếu hiện ở đây ngay (và bạn nhận thông báo).</Empty>
              : <ul className="ui-list">{queue.map((visit, index) => <VisitLine key={visit.ticket?.id ?? visit.id} visit={visit} highlight={index === 0}>
                {index === 0 && <Badge tone="blue">Kế tiếp</Badge>}
                {visit.ticket && visit.ticket.priority > 0 && <Badge map={TICKET_PRIORITY} value={visit.ticket.priority} />}
                {calledLabel(visit) && <Badge tone="amber">{calledLabel(visit)}</Badge>}
                <Button className="small" disabled={busy || !visit.ticket} onClick={() => call(visit)}>{visit.ticket?.lastCalledAt ? 'Gọi lại' : 'Gọi'}</Button>
                <Button className="small primary" disabled={busy} onClick={() => start(visit)}>{visit.status === 'RESULTS_READY' ? 'Bắt đầu giai đoạn 2' : 'Bắt đầu khám'}</Button>
                <Link className="ui-button small" to={`/vet/visits/${visit.id}`}>Xem hồ sơ</Link>
              </VisitLine>)}</ul>}
            {busy && queue.length > 0 && <p className="ui-muted" style={{ marginBottom: 0 }}>Bạn đang có ca đang khám nên chưa gọi hay bắt đầu ca khác được.</p>}
          </Panel>
        </>;
      }}
    </AsyncView>
  </StaffLayout>;
}
