--计数器的键
local counter_count_key = KEYS[1]
--时间戳的键
local counter_timestamp_key = KEYS[2]
--校验验证码id的键
local verify_captcha_id = KEYS[3]
--每秒最大请求次数
local verify_captcha_threshold = tonumber(ARGV[1])
--当前时间戳
local current_time_millis = tonumber(ARGV[2])
--校验验证码id过期时间
local verify_captcha_id_expire_time = tonumber(ARGV[3])
--始终开启校验验证码开关 0:不开启 1:开启
local always_verify_captcha = tonumber(ARGV[4])
--时间窗口大小,1000毫秒,即1秒
local differenceValue = 1000
-- 如果开启校验验证码开关, 则直接返回结束
if always_verify_captcha == 1 then
    redis.call('set', verify_captcha_id,'yes')
    redis.call('expire',verify_captcha_id,verify_captcha_id_expire_time)
    return 'true'
end
-- 获取当前计数和上次重置时间
local count = tonumber(redis.call('get', counter_count_key) or "0")
local lastResetTime = tonumber(redis.call('get', counter_timestamp_key) or "0")
-- 检查时间窗口是否已过, 如果是,则重置技术和时间戳
if current_time_millis - lastResetTime > differenceValue then
    count = 0
    redis.call('set', counter_count_key, count)
    redis.call('set', counter_timestamp_key, current_time_millis)
end
-- 更新计数
count = count + 1
-- 超过阈值限制
if count > verify_captcha_threshold then
    -- 重置计数和时间戳
    count = 0
    redis.call('set', counter_count_key, count)
    redis.call('set', counter_timestamp_key, current_time_millis)
    redis.call('set', verify_captcha_id,'yes')
    redis.call('expire',verify_captcha_id,verify_captcha_id_expire_time)
    return 'true'
end
redis.call('set', counter_count_key, count)
redis.call('set',verify_captcha_id,'no')
redis.call('expire',verify_captcha_id,verify_captcha_id_expire_time)
return 'false'
