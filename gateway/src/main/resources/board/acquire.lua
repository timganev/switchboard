-- KEYS: one sorted set of leases per candidate instance; ARGV[1]: lease id, ARGV[2]: lease ttl in ms
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local best, bestCount = nil, nil
for _, key in ipairs(KEYS) do
  redis.call('ZREMRANGEBYSCORE', key, '-inf', now)
  local count = redis.call('ZCARD', key)
  if bestCount == nil or count < bestCount then
    best, bestCount = key, count
  end
end
redis.call('ZADD', best, now + tonumber(ARGV[2]), ARGV[1])
redis.call('PEXPIRE', best, ARGV[2])
return best
