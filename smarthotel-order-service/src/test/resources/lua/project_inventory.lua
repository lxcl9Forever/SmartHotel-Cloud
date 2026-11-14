-- KEYS: quantity, projection revision. ARGV: monotonic database revision, absolute quantity.
local previous = redis.call('GET', KEYS[2])
if previous and tonumber(previous) > tonumber(ARGV[1]) then
    return 0
end
redis.call('SET', KEYS[1], ARGV[2])
redis.call('SET', KEYS[2], ARGV[1])
return 1
