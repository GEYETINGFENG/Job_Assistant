-- 每次 LLM 调用前，它负责判断有没有额度，有就扣掉，没有就告诉你要等多久
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local ttl = tonumber(ARGV[3])
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local state = redis.call('HMGET', KEYS[1], 'tokens', 'last_refill_ms')
local tokens = tonumber(state[1])
local last = tonumber(state[2])

if redis.call('EXISTS', KEYS[1]) == 0 then
    tokens = capacity
    last = now
elseif tokens == nil or last == nil or tokens ~= tokens or last ~= last
        or tokens < 0 or last < 0 or tokens == math.huge or last == math.huge then
    -- 损坏状态不能自动补满，否则会额外放行模型请求。
    return redis.error_reply('Invalid LLM token bucket state')
end

-- 时间回拨时不重复补充已经计算过的时间段。
local effective_now = math.max(now, last)
tokens = math.min(capacity, tokens + (effective_now - last) * rate / 1000)
local allowed = 0
local wait_ms = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
else
    wait_ms = math.max(1, math.ceil((1 - tokens) * 1000 / rate + effective_now - now))
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'last_refill_ms', tostring(effective_now))
-- TTL 大于从空桶回满的时间；回拨时也不能提前过期而凭空补满。
redis.call('PEXPIRE', KEYS[1], ttl + effective_now - now)
return {allowed, wait_ms}
