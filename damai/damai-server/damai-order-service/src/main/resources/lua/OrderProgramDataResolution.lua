-- 订单操作的类型 2:取消订单 3:订单支付
local operate_order_status = tonumber(KEYS[1])
-- 解锁锁定的座位id列表
local un_lock_seat_id_json_array = cjson.decode(ARGV[1])
-- 座位数据
local add_seat_data_json_array = cjson.decode(ARGV[2])
-- 将锁定的座位集合进行扣除
for index, un_lock_seat_id_json_object in pairs(un_lock_seat_id_json_array) do
    local program_seat_hash_key = un_lock_seat_id_json_object.programSeatLockHashKey
    local un_lock_seat_id_list = un_lock_seat_id_json_object.unLockSeatIdList
    -- program_seat_hash_key: Redis中Hash的key,表示某个节目下的座位信息
    -- unpack(un_lock_seat_id_list)：将Lua表（数组）拆分成多个参数，作为HDEL的field参数
    -- 总的业务逻辑就是批量删除Hash中的字段,通常用于释放/解锁座位, 让这些座位重新变为可售状态
    redis.call('HDEL',program_seat_hash_key,unpack(un_lock_seat_id_list))    
end

-- 如果是订单取消的操作, 那么添加到未售卖的座位hash数据
-- 如果是订单支付的操作, 那么添加到已售卖的座位hash数据
for index, add_seat_data_json_object in pairs(add_seat_data_json_array) do
    local seat_hash_key_add = add_seat_data_json_object.seatHashKeyAdd
    local seat_data_list = add_seat_data_json_object.seatDataList
    redis.call('HMSET',seat_hash_key_add,unpack(seat_data_list))
end

-- 如果是将订单取消
if (operate_order_status == 2) then
    -- 票档数量数据
    local ticket_category_list = cjson.decode(ARGV[3])
    for index,increase_data in ipairs(ticket_category_list) do
        -- 票档数量的key
        local program_ticket_remain_number_hash_key = increase_data.programTicketRemainNumberHashKey
        local ticket_category_id = increase_data.ticketCategoryId
        local increase_count = increase_data.count
        redis.call('HINCRBY',program_ticket_remain_number_hash_key,ticket_category_id,increase_count)
    end
end