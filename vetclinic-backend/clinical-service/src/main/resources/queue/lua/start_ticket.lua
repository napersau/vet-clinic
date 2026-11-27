-- Bắt đầu phục vụ phiếu (Quy chuẩn v4.0 mục 9.3, mục 2): WAITING -> IN_SERVICE, rời hàng đợi chờ.
-- Phiếu DOCTOR: một Bác sĩ chỉ phục vụ một phiếu một lúc (khóa queue:busy:{doctorId}, BR-12).
-- Nhiều yêu cầu "Bắt đầu" đồng thời trên cùng một phiếu: Redis chạy tuần tự từng script nên đúng MỘT yêu cầu nhận OK, còn lại NOT_WAITING.
-- KEYS[1] = ticket:{ticketId}   ARGV[1] = ticketId  ARGV[2] = thời điểm bắt đầu (epoch millis)  ARGV[3] = TTL khóa busy (giây)
-- Trả về: OK | NOT_FOUND | NOT_WAITING | UNASSIGNED | DOCTOR_BUSY
local f = redis.call('HMGET', KEYS[1], 'status', 'queueType', 'serviceDate', 'doctorId')
if not f[1] then return 'NOT_FOUND' end
if f[1] ~= 'WAITING' then return 'NOT_WAITING' end
local owner = 'ALL'
local busy = nil
if f[2] == 'DOCTOR' then
  if not f[4] or f[4] == '' then return 'UNASSIGNED' end
  owner = f[4]
  busy = 'queue:busy:' .. f[4]
  if redis.call('EXISTS', busy) == 1 then return 'DOCTOR_BUSY' end
end
redis.call('HSET', KEYS[1], 'status', 'IN_SERVICE', 'startedAt', ARGV[2])
if busy then redis.call('SET', busy, ARGV[1], 'EX', tonumber(ARGV[3])) end
redis.call('ZREM', 'queue:' .. f[3] .. ':' .. f[2] .. ':' .. owner, ARGV[1])
return 'OK'
