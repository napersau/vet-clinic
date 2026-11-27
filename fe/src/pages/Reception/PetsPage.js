import { useState } from 'react';
import { Link } from 'react-router-dom';
import StaffLayout from '../../layouts/StaffLayout';
import PetFormDialog from '../../components/shared/PetFormDialog';
import { AsyncView, Button, ConfirmDialog, Notice, PageHead, Pager, Panel, SearchBox, Table } from '../../components/ui';
import { petsApi } from '../../api/endpoints';
import useAsync from '../../hooks/useAsync';
import useDebounced from '../../hooks/useDebounced';
import { formatAge } from '../../lib/format';
import { PET_GENDER } from '../../lib/labels';

export default function PetsPage() {
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState(null);
  const [notice, setNotice] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const search = useDebounced(keyword);
  // Gõ đúng số điện thoại (10 số) thì tìm theo SĐT chủ nuôi (cột snapshot trên hồ sơ thú cưng, không gọi auth-service)
  const term = search.trim();
  const byPhone = /^0\d{9}$/.test(term);
  const list = useAsync(() => petsApi.list({ keyword: byPhone ? undefined : term || undefined, ownerPhone: byPhone ? term : undefined, page, size: 15 }), [term, page]);
  return <StaffLayout area="reception" title="Thú cưng">
    <PageHead eyebrow="THÚ CƯNG" title="Thông tin thú cưng">Tra cứu hồ sơ theo tên, mã thú cưng, số vi mạch hoặc số điện thoại của chủ nuôi. Thêm thú cưng mới từ hồ sơ của chủ nuôi để gắn đúng người.</PageHead>
    <Notice notice={notice} onClose={() => setNotice(null)} />
    <div className="ui-toolbar"><SearchBox value={keyword} onChange={(value) => { setKeyword(value); setPage(0); }} placeholder="Tên, mã (PET-…), vi mạch hoặc SĐT chủ nuôi" label="Tìm thú cưng" /></div>
    <Panel flush>
      <AsyncView state={list} isEmpty={(data) => data.items.length === 0} empty="Không tìm thấy thú cưng nào.">
        {(data) => <>
          <Table caption="Thú cưng" rows={data.items} columns={[
            { title: 'Thú cưng', render: (row) => <><strong>{row.name}</strong><small>{row.petCode}</small></> },
            { title: 'Loài / giống', render: (row) => `${row.speciesName}${row.breed ? ` · ${row.breed}` : ''}` },
            { title: 'Giới tính / tuổi', render: (row) => `${PET_GENDER[row.gender] || ''} · ${formatAge(row.dateOfBirth)}` },
            { title: 'Chủ nuôi', render: (row) => <><Link className="ui-link" to={`/reception/customers/${row.ownerId}`}>{row.ownerName}</Link><small>{row.ownerPhone}</small></> },
            { title: 'Lưu ý', render: (row) => <>{row.allergies ? <strong>Dị ứng: {row.allergies}</strong> : null}{row.allergies && row.notes ? <br /> : null}{row.notes || (row.allergies ? null : '—')}</> },
            { title: '', align: 'right', render: (row) => <div className="ui-actions" style={{ justifyContent: 'flex-end' }}><Button className="small" onClick={() => setEditing(row)}>Sửa</Button><Button className="small danger" onClick={() => setDeleting(row)}>Xóa</Button><Link className="ui-button small" to={`/reception/history?petId=${row.id}`}>Bệnh án</Link></div> }]} />
          <Pager page={data} onChange={setPage} />
        </>}
      </AsyncView>
    </Panel>
    {deleting && <ConfirmDialog title={`Xóa hồ sơ ${deleting.name}?`} danger confirmLabel="Xóa hồ sơ" onClose={() => setDeleting(null)} onConfirm={async () => { await petsApi.remove(deleting.id); setNotice({ type: 'success', text: `Đã xóa hồ sơ của ${deleting.name}.` }); list.refresh(); }}>Hồ sơ bị ẩn khỏi danh sách và không đặt lịch / tiếp đón được nữa; bệnh án cũ vẫn được lưu. Không xóa được khi thú cưng đang có đợt khám chưa kết thúc.</ConfirmDialog>}
    {editing && <PetFormDialog pet={editing} onClose={() => setEditing(null)} onSaved={(saved) => { setNotice({ type: 'success', text: `Đã lưu hồ sơ của ${saved.name}.` }); list.refresh(); }} />}
  </StaffLayout>;
}
