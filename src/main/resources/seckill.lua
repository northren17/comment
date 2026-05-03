-- KEYS[1]: seckill stock key, e.g. seckill:stock:{voucherId}
-- KEYS[2]: seckill order key, e.g. seckill:order:{voucherId}
-- ARGV[1]: userId
-- ARGV[2]: voucherId
-- ARGV[3]: orderId

local stock = tonumber(redis.call('get', KEYS[1]))
if (not stock) then
    return 3
end

if (stock <= 0) then
    return 1
end

if (redis.call('sismember', KEYS[2], ARGV[1]) == 1) then
    return 2
end

redis.call('incrby', KEYS[1], -1)
redis.call('sadd', KEYS[2], ARGV[1])
redis.call('xadd', 'stream.orders', '*',
        'voucherId', ARGV[2],
        'userId', ARGV[1],
        'orderId', ARGV[3])

return 0
