package com.damai.servicelock.aspect;

import com.damai.constant.LockInfoType;
import com.damai.util.StringUtil;
import com.damai.lockinfo.LockInfoHandle;
import com.damai.lockinfo.factory.LockInfoHandleFactory;
import com.damai.servicelock.LockType;
import com.damai.servicelock.ServiceLocker;
import com.damai.servicelock.annotion.ServiceLock;
import com.damai.servicelock.factory.ServiceLockFactory;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * @program: 极度真实还原大麦网高并发实战项目。 添加 阿星不是程序员 微信，添加时备注 大麦 来获取项目的完整资料 
 * @description: 分布式锁 切面
 * @author: 阿星不是程序员
 **/
@Slf4j
@Aspect
@Order(-10)
@AllArgsConstructor
public class ServiceLockAspect {
    
    private final LockInfoHandleFactory lockInfoHandleFactory;
    
    private final ServiceLockFactory serviceLockFactory;
    

    @Around("@annotation(servicelock)")
    public Object around(ProceedingJoinPoint joinPoint, ServiceLock servicelock) throws Throwable {
        //获取锁的名字解析处理器
        LockInfoHandle lockInfoHandle = lockInfoHandleFactory.getLockInfoHandle(LockInfoType.SERVICE_LOCK);
        //拼接锁的名字 LOCK:${name}:${keys}
        String lockName = lockInfoHandle.getLockName(joinPoint, servicelock.name(),servicelock.keys());
        //锁的类型，默认 可重入锁
        LockType lockType = servicelock.lockType();
        //尝试加锁失败最多等待时间，默认10s
        long waitTime = servicelock.waitTime();
        //时间单位，默认秒
        TimeUnit timeUnit = servicelock.timeUnit();
        //获得具体的锁类型
        ServiceLocker lock = serviceLockFactory.getLock(lockType);
        //进行加锁
        boolean result = lock.tryLock(lockName, timeUnit, waitTime);
        //如果加锁成功
        if (result) {
            try {
                //执行业务逻辑
                return joinPoint.proceed();
            }finally{
                //解锁
                lock.unlock(lockName);
            }
        }else {
            log.warn("Timeout while acquiring serviceLock:{}",lockName);
            //加锁失败,如果设置了自定义处理，则执行
            String customLockTimeoutStrategy = servicelock.customLockTimeoutStrategy();
            if (StringUtil.isNotEmpty(customLockTimeoutStrategy)) {
                return handleCustomLockTimeoutStrategy(customLockTimeoutStrategy, joinPoint);
            }else{
                //默认处理
                servicelock.lockTimeoutStrategy().handler(lockName);
            }
            return joinPoint.proceed();
        }
    }

    public Object handleCustomLockTimeoutStrategy(String customLockTimeoutStrategy,JoinPoint joinPoint) {
        // prepare invocation context
        Method currentMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object target = joinPoint.getTarget();
        Method handleMethod = null;
        try {
            handleMethod = target.getClass().getDeclaredMethod(customLockTimeoutStrategy, currentMethod.getParameterTypes());
            handleMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Illegal annotation param customLockTimeoutStrategy :" + customLockTimeoutStrategy,e);
        }
        Object[] args = joinPoint.getArgs();

        // invoke
        Object result;
        try {
            result = handleMethod.invoke(target, args);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Fail to illegal access custom lock timeout handler: " + customLockTimeoutStrategy ,e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("Fail to invoke custom lock timeout handler: " + customLockTimeoutStrategy ,e);
        }
        return result;
    }
}
