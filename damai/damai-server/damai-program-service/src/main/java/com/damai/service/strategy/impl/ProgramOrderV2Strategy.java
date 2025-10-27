package com.damai.service.strategy.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.damai.core.RepeatExecuteLimitConstants;
import com.damai.dto.ProgramOrderCreateDto;
import com.damai.dto.SeatDto;
import com.damai.enums.CompositeCheckType;
import com.damai.enums.ProgramOrderVersion;
import com.damai.initialize.base.AbstractApplicationCommandLineRunnerHandler;
import com.damai.initialize.impl.composite.CompositeContainer;
import com.damai.locallock.LocalLockCache;
import com.damai.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.damai.service.ProgramOrderService;
import com.damai.service.strategy.ProgramOrderContext;
import com.damai.service.strategy.ProgramOrderStrategy;
import com.damai.servicelock.LockType;
import com.damai.util.ServiceLockTool;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static com.damai.core.DistributedLockConstants.PROGRAM_ORDER_CREATE_V2;

/**
 * @program: 极度真实还原大麦网高并发实战项目。 添加 阿星不是程序员 微信，添加时备注 大麦 来获取项目的完整资料 
 * @description: 节目订单v2
 * @author: 阿星不是程序员
 **/
@Slf4j
@Component
public class ProgramOrderV2Strategy extends AbstractApplicationCommandLineRunnerHandler implements ProgramOrderStrategy {
    
    @Autowired
    private ProgramOrderService programOrderService;
    
    @Autowired
    private ServiceLockTool serviceLockTool;
    
    @Autowired
    private CompositeContainer compositeContainer;
    
    @Autowired
    private LocalLockCache localLockCache;
    
    
    @RepeatExecuteLimit(
            name = RepeatExecuteLimitConstants.CREATE_PROGRAM_ORDER,
            keys = {"#programOrderCreateDto.userId","#programOrderCreateDto.programId"})
    @Override
    public String createOrder(ProgramOrderCreateDto programOrderCreateDto) {
        //业务参数验证
        compositeContainer.execute(CompositeCheckType.PROGRAM_ORDER_CREATE_CHECK.getValue(),programOrderCreateDto);
        List<SeatDto> seatDtoList = programOrderCreateDto.getSeatDtoList();
        List<Long> ticketCategoryIdList = new ArrayList<>();
        //手段选择座位时,统计出票档id
        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            ticketCategoryIdList =
                    seatDtoList.stream().map(SeatDto::getTicketCategoryId).distinct().collect(Collectors.toList());
        }else {
            //自动匹配座位时,传入的票档id
            ticketCategoryIdList.add(programOrderCreateDto.getTicketCategoryId());
        }
        //本地锁集合
        List<ReentrantLock> localLockList = new ArrayList<>(ticketCategoryIdList.size());
        //分布式锁集合
        List<RLock> serviceLockList = new ArrayList<>(ticketCategoryIdList.size());
        //加锁成功的本地锁集合
        List<ReentrantLock> localLockSuccessList = new ArrayList<>(ticketCategoryIdList.size());
        //加锁成功的分布式锁集合
        List<RLock> serviceLockSuccessList = new ArrayList<>(ticketCategoryIdList.size());
        //根据统计出的票档id获得本地锁和分布式锁集合
        for (Long ticketCategoryId : ticketCategoryIdList) {
            //锁的key为d_program_order_create_v2_lock-programId-ticketCategoryId
            String lockKey = StrUtil.join("-",PROGRAM_ORDER_CREATE_V2,
                    programOrderCreateDto.getProgramId(),ticketCategoryId);
            //获得本地锁实例
            ReentrantLock localLock = localLockCache.getLock(lockKey,false);
            //获得分布式锁实例
            RLock serviceLock = serviceLockTool.getLock(LockType.Reentrant, lockKey);
            //添加到本地锁集合
            localLockList.add(localLock);
            //添加到分布式锁集合
            serviceLockList.add(serviceLock);
        }
        //循环分布式锁进行加锁
        for (ReentrantLock reentrantLock : localLockList) {
            try {
                reentrantLock.lock();
            }catch (Throwable t) {
                //如果加锁出现异常,则终止
                break;
            }
            localLockSuccessList.add(reentrantLock);
        }
        //循环分布式锁进行加锁
        for (RLock rLock : serviceLockList) {
            try {
                rLock.lock();
            }catch (Throwable t) {
                //如果加锁出现异常,则终止
                break;
            }
            serviceLockSuccessList.add(rLock);
        }
        try {
            //进行订单创建
            return programOrderService.create(programOrderCreateDto);
        }finally {
            //先循环解锁分布式锁
            for (int i = serviceLockSuccessList.size() - 1; i >= 0; i--) {
                RLock rLock = serviceLockSuccessList.get(i);
                try {
                    rLock.unlock();
                }catch (Throwable t) {
                    log.error("service lock unlock error",t);
                }
            }
            //再循环解锁本地锁
            for (int i = localLockSuccessList.size() - 1; i >= 0; i--) {
                ReentrantLock reentrantLock = localLockSuccessList.get(i);
                try {
                    reentrantLock.unlock();
                }catch (Throwable t) {
                    log.error("local lock unlock error",t);
                }
            }
        }
    }
    
    @Override
    public Integer executeOrder() {
        return 2;
    }
    
    @Override
    public void executeInit(final ConfigurableApplicationContext context) {
        ProgramOrderContext.add(ProgramOrderVersion.V2_VERSION.getVersion(),this);
    }
}
