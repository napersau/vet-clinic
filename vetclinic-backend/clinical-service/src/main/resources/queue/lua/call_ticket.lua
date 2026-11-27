-- "Gọi" / "Gọi lại": chỉ tăng calledCount và ghi lastCalledAt, KHÔNG đổi thứ tự, KHÔNG đổi trạng thái (BR-34).
-- KEYS[1] = ticket:{ticketId}   ARGV[1] = thời điểm gọi (epoch millis)
-- Trả về { OK | NOT_FOUND | NOT_WAITING, calledCount }
local status = redis.call('HGET', KEYS[1], 'status')
if not status then return { 'NOT_FOUND', '0' } end
if status ~= 'WAITING' then return { 'NOT_WAITING', '0' } end
local n = redis.call('HINCRBY', KEYS[1], 'calledCount', 1)
redis.call('HSET', KEYS[1], 'lastCalledAt', ARGV[1])
return { 'OK', tostring(n) }
