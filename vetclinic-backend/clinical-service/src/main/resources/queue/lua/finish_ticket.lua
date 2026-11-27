-- Kết thúc / hủy phiếu (Quy chuẩn v4.0 mục 9.3, mục 3): gỡ khỏi hàng đợi (nếu còn chờ), khỏi tập phiếu của đợt khám, nhả khóa busy của
-- Bác sĩ nếu đúng phiếu này, xóa Hash phiếu. Gọi SAU KHI giao dịch MySQL ghi queue_ticket_histories đã commit. Chạy lại nhiều lần vẫn an toàn.
-- KEYS[1] = ticket:{ticketId}   ARGV[1] = ticketId
-- Trả về: 1 nếu phiếu còn tồn tại và đã xóa, 0 nếu phiếu không còn (đã xóa trước đó)
local f = redis.call('HMGET', KEYS[1], 'queueType', 'serviceDate', 'doctorId', 'visitId')
if not f[1] then return 0 end
local owner = 'ALL'
if f[1] == 'DOCTOR' then
  if f[3] and f[3] ~= '' then owner = f[3] else owner = 'UNASSIGNED' end
end
redis.call('ZREM', 'queue:' .. f[2] .. ':' .. f[1] .. ':' .. owner, ARGV[1])
if f[4] and f[4] ~= '' then redis.call('SREM', 'queue:visit:' .. f[4], ARGV[1]) end
if f[1] == 'DOCTOR' and f[3] and f[3] ~= '' then
  local busy = 'queue:busy:' .. f[3]
  if redis.call('GET', busy) == ARGV[1] then redis.call('DEL', busy) end
end
redis.call('DEL', KEYS[1])
return 1
