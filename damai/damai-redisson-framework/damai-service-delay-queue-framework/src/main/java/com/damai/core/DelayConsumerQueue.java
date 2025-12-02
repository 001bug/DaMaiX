package com.damai.core;

import com.damai.context.DelayQueuePart;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @program: 极度真实还原大麦网高并发实战项目。 添加 阿星不是程序员 微信，添加时备注 大麦 来获取项目的完整资料 
 * @description: 延迟队列 消费
 * @author: 阿星不是程序员
 **/
@Slf4j
public class DelayConsumerQueue extends DelayBaseQueue{
    //监听消息线程数
    private final AtomicInteger listenStartThreadCount = new AtomicInteger(1);
    //消费消息线程数
    private final AtomicInteger executeTaskThreadCount = new AtomicInteger(1);
    //监听消息线程池
    private final ThreadPoolExecutor listenStartThreadPool;
    //消费消息线程池
    private final ThreadPoolExecutor executeTaskThreadPool;
    //监控消费启动标志
    private final AtomicBoolean runFlag = new AtomicBoolean(false);
    //消息处理
    private final ConsumerTask consumerTask;
    
    public DelayConsumerQueue(DelayQueuePart delayQueuePart, String relTopic){
        //构建RBlockingQueue
        super(delayQueuePart.getDelayQueueBasePart().getRedissonClient(),relTopic);
        //监听消息线程池
        this.listenStartThreadPool = new ThreadPoolExecutor(1,1,60, 
                TimeUnit.SECONDS,new LinkedBlockingQueue<>(),r -> new Thread(Thread.currentThread().getThreadGroup(), r,
                "listen-start-thread-" + listenStartThreadCount.getAndIncrement()));
        //消费消息线程池
        this.executeTaskThreadPool = new ThreadPoolExecutor(
                delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getCorePoolSize(),
                delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getMaximumPoolSize(),
                delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getKeepAliveTime(),
                delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getUnit(),
                new LinkedBlockingQueue<>(delayQueuePart.getDelayQueueBasePart().getDelayQueueProperties().getWorkQueueSize()),
                r -> new Thread(Thread.currentThread().getThreadGroup(), r, 
                        "delay-queue-consume-thread-" + executeTaskThreadCount.getAndIncrement()));
        //消息处理逻辑
        this.consumerTask = delayQueuePart.getConsumerTask();
    }
    //轮询的方式实现监听回调的机制
    public synchronized void listenStart(){
        //如果runFlag为false, 说明监听没有启动
        if (!runFlag.get()) {
            //将runFlag设置为true. 表示监听已经启动
            runFlag.set(true);
            //异步执行监听逻辑
            listenStartThreadPool.execute(() -> {
                while (!Thread.interrupted()) {
                    try {
                        assert blockingQueue != null;
                        //从Redisson的RBlockingQueue中监听消息
                        String content = blockingQueue.take();
                        //如果监听到消息, 则异步执行处理逻辑
                        executeTaskThreadPool.execute(() -> {
                            try {
                                consumerTask.execute(content);
                            }catch (Exception e) {
                                log.error("consumer execute error",e);
                            }
                        });
                    } catch (InterruptedException e) {
                        //如果出现中断异常, 则将线程池关闭
                        destroy(executeTaskThreadPool);
                    } catch (Throwable e) {
                        log.error("blockingQueue take error",e);
                    }
                }
            });
        }
    }
    
    public void destroy(ExecutorService executorService) {
        try {
            if (Objects.nonNull(executorService)) {
                executorService.shutdown();
            }
        } catch (Exception e) {
            log.error("destroy error",e);
        }
    }
}
