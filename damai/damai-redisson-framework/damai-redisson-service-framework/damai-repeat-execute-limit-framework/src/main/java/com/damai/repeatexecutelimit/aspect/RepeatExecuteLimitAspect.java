package com.damai.repeatexecutelimit.aspect;

import com.damai.constant.LockInfoType;
import com.damai.exception.DaMaiFrameException;
import com.damai.handle.RedissonDataHandle;
import com.damai.locallock.LocalLockCache;
import com.damai.lockinfo.LockInfoHandle;
import com.damai.lockinfo.factory.LockInfoHandleFactory;
import com.damai.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.damai.servicelock.LockType;
import com.damai.servicelock.ServiceLocker;
import com.damai.servicelock.factory.ServiceLockFactory;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.damai.repeatexecutelimit.constant.RepeatExecuteLimitConstant.PREFIX_NAME;
import static com.damai.repeatexecutelimit.constant.RepeatExecuteLimitConstant.SUCCESS_FLAG;

/**
 /**
 * @program: 极度真实还原大麦网高并发实战项目。 添加 阿星不是程序员 微信，添加时备注 大麦 来获取项目的完整资料 
 * @description: 防重复执行,幂等性控制,双重锁机制(本地锁+分布式锁) 切面
 * reids快速检查->本地锁->redisson分布式锁->执行业务(双重检查)->释放锁
 * 请求进入
 *     ↓
 * ┌─────────────────┐
 * │ 1. Redis快速检查  │ ← 最快速的拒绝重复请求
 * └─────────────────┘
 *     ↓
 * ┌─────────────────┐
 * │ 2. 本地锁保护    │ ← 同一JVM内的并发控制
 * └─────────────────┘
 *     ↓
 * ┌─────────────────┐
 * │ 3. 分布式锁保护  │ ← 跨JVM的并发控制
 * └─────────────────┘
 *     ↓
 * ┌─────────────────┐
 * │ 4. 二次Redis检查 │ ← 双重检查，确保一致性
 * └─────────────────┘
 *     ↓
 * ┌─────────────────┐
 * │ 5. 执行业务逻辑  │
 * └─────────────────┘
 *     ↓
 * ┌─────────────────┐
 * │ 6. 设置成功标记  │ ← 标记已执行，防止后续重复
 * └─────────────────┘
 * @author: 阿星不是程序员
 **/
@Slf4j
@Aspect
@Order(-11)
@AllArgsConstructor
public class RepeatExecuteLimitAspect {
    //本地锁缓存的封装
    private final LocalLockCache localLockCache;
    
    private final LockInfoHandleFactory lockInfoHandleFactory;
    
    private final ServiceLockFactory serviceLockFactory;
    //是Redission操作的封装类, 简化redis数据操作方法
    private final RedissonDataHandle redissonDataHandle;
    
    
    @Around("@annotation(repeatLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RepeatExecuteLimit repeatLimit) throws Throwable {
        long durationTime = repeatLimit.durationTime(); //幂等执行时间
        String message = repeatLimit.message(); //错误提示信息
        Object obj;
        //获取锁的信息
        LockInfoHandle lockInfoHandle = lockInfoHandleFactory.getLockInfoHandle(LockInfoType.REPEAT_EXECUTE_LIMIT);
        //锁的名字
        String lockName = lockInfoHandle.getLockName(joinPoint,repeatLimit.name(), repeatLimit.keys());
        //幂等标识
        String repeatFlagName = PREFIX_NAME + lockName;
        //获取幂等标识(从redis中查找)
        String flagObject = redissonDataHandle.get(repeatFlagName);
        //如果已经执行过了,则直接抛出异常, 这次请求直接结束
        if (SUCCESS_FLAG.equals(flagObject)) {
            throw new DaMaiFrameException(message);
        }
        //获取本地锁
        ReentrantLock localLock = localLockCache.getLock(lockName,true);
        //本地锁获取锁
        boolean localLockResult = localLock.tryLock();
        //如果本地锁获取失败,则直接抛出异常, 这次请求直接结束
        if (!localLockResult) {
            throw new DaMaiFrameException(message);
        }
        try {
            //尝试获取分布式锁
            ServiceLocker lock = serviceLockFactory.getLock(LockType.Fair);
            //分布式锁获取锁
            boolean result = lock.tryLock(lockName, TimeUnit.SECONDS, 0);
            //加锁成功
            if (result) {
                try{
                    //再次从redis中获取幂等标识
                    flagObject = redissonDataHandle.get(repeatFlagName);
                    //如果已经执行过了,则直接抛出异常, 这次请求直接结束,再次检查防止并发问题
                    if (SUCCESS_FLAG.equals(flagObject)) {
                        throw new DaMaiFrameException(message);
                    }
                    obj = joinPoint.proceed(); //执行真正的业务
                    if (durationTime > 0) {//执行成功后,设置幂等标识,在指定的时间内不允许再次执行
                        try {
                            redissonDataHandle.set(repeatFlagName,SUCCESS_FLAG,durationTime,TimeUnit.SECONDS);
                        }catch (Exception e) {
                            log.error("getBucket error",e);
                        }
                    }
                    return obj;
                } finally {
                    //释放分布式锁
                    lock.unlock(lockName);
                }
            }else{
                throw new DaMaiFrameException(message);
            }
        }finally {
            localLock.unlock();
        }
    }
}
