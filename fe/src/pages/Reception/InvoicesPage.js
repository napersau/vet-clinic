import { useState } from 'react';
import { Link } from 'react-router-dom';
import StaffLayout from '../../layouts/StaffLayout';
import { AsyncView, Badge, Button, Input, PageHead, Pager, Panel, Table, Tabs } from '../../components/ui';
import { visitsApi } from '../../api/endpoints';
import { useNotificationEvents } from '../../contexts/NotificationContext';
import useAsync from '../../hooks/useAsync';
import useDebounced from '../../hooks/useDebounced';
import usePolling from '../../hooks/usePolling';
import { formatDateTime } from '../../lib/format';
import { VISIT_STATUS } from '../../lib/labels';

const TABS = [
  { key: 'EXAM_FEE', label: 'Phí khám (chờ vào hàng đợi)' },
  { key: 'PARACLINIC', label: 'Xét nghiệm / CĐHA' },
  { key: 'FINAL', label: 'Chốt ca' },
  { key: 'DEBT', label: 'Công nợ (khách bỏ về)' },
];
const EMPTY = {
  EXAM_FEE: 'Không có ca nào chờ nộp phí khám.',
  PARACLINIC: 'Không có ca nào chờ nộp tiền xét nghiệm / CĐHA.',
  FINAL: 'Không có ca nào đang chờ thanh toán.',
  DEBT: 'Không có công nợ nào.',
};
const ACTION = { EXAM_FEE: 'Thu phí khám', PARACLINIC: 'Thu tiền XN / CĐHA', FINAL: 'Thu tiền', DEBT: 'Thu công nợ' };

/**
 * Thu ngân (cập nhật 17/11/2026, BR-35): thu tiền tách khỏi khám. Khám, XN, CĐHA thu TRƯỚC (thu xong hệ thống phát phiếu xếp hàng);
 * dịch vụ làm tại phòng thu khi bác sĩ đã chốt ca. Ba mục ứng với ba lần khách ra quầy, cộng công nợ của khách bỏ về.
 */
export default function InvoicesPage() {
  const [tab, setTab] = useState('EXAM_FEE');
  const [page, setPage] = useState(0);
  const [keyword, setKeyword] = useState('');
  const search = useDebounced(keyword);
  const list = useAsync(async () => {
    if (tab === 'DEBT') {
      const items = await visitsApi.withDebt();
      const needle = search.trim().toLowerCase();
      const filtered = needle ? items.filter((visit) => `${visit.visitCode} ${visit.petName}`.toLowerCase().includes(needle)) : items;
      return { items: filtered, page: 0, size: filtered.length, totalElements: filtered.length, totalPages: 1, last: true };
    }
    const items = await visitsApi.cashierPending(tab);
    const needle = search.trim().toLowerCase();
    const filtered = needle ? items.filter((visit) => `${visit.visitCode} ${visit.petName}`.toLowerCase().includes(needle)) : items;
    return { items: filtered, page: 0, size: filtered.length, totalElements: filtered.length, totalPages: 1, last: true };
  }, [tab, search, page]);
  useNotificationEvents(() => list.refresh(), ['VISIT_CHECKED_IN', 'TICKET_ISSUED', 'VISIT_COMPLETED']);
  usePolling(list.refresh, 30000);

  return <StaffLayout area="reception" title="Hóa đơn & thanh toán">
    <PageHead eyebrow="THU NGÂN" title="Hóa đơn & thanh toán" actions={<Button onClick={list.reload}>Làm mới</Button>}>
      Khám, xét nghiệm, CĐHA thu trước: thu xong hệ thống phát phiếu số thứ tự để khách vào hàng đợi. Dịch vụ làm tại phòng (tiêm, tẩy giun…) và thuốc thu khi bác sĩ chốt ca. Ca hoàn tất khi mọi hóa đơn dịch vụ đã thu, hủy hoặc hoàn tiền.
    </PageHead>
    <Tabs tabs={TABS} value={tab} onChange={(key) => { setTab(key); setPage(0); }} />
    <div className="ui-toolbar"><Input aria-label="Tìm đợt khám" placeholder="Mã ca hoặc tên thú cưng" value={keyword} onChange={(event) => { setKeyword(event.target.value); setPage(0); }} /></div>
    <Panel flush>
      <AsyncView state={list} isEmpty={(data) => data.items.length === 0} empty={EMPTY[tab]}>
        {(data) => <>
          <Table caption="Đợt khám cần thu tiền" rows={data.items} columns={[
            { title: 'Mã ca', render: (row) => <Link className="ui-link" to={`/reception/invoices/${row.id}`}><strong>{row.visitCode}</strong></Link> },
            { title: 'Thú cưng', render: (row) => <>{row.petName}<small>{row.speciesName}</small></> },
            { title: 'Tiếp nhận', render: (row) => formatDateTime(row.checkedInAt) },
            { title: 'Trạng thái ca', render: (row) => <Badge map={VISIT_STATUS} value={row.status} /> },
            { title: '', align: 'right', render: (row) => <Link className="ui-button small primary" to={`/reception/invoices/${row.id}`}>{ACTION[tab]}</Link> }]} />
          <Pager page={data} onChange={setPage} />
        </>}
      </AsyncView>
    </Panel>
  </StaffLayout>;
}
