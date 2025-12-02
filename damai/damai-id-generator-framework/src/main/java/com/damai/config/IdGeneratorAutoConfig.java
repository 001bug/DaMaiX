package com.damai.config;

import com.damai.toolkit.SnowflakeIdGenerator;
import com.damai.toolkit.WorkAndDataCenterIdHandler;
import com.damai.toolkit.WorkDataCenterId;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * @program: 极度真实还原大麦网高并发实战项目。
 * @description: 分布式id配置
 * @author: 阿星不是程序员
 **/
public class IdGeneratorAutoConfig {
    //执行lua脚本的执行器, 执行完脚本后获得了workId和dataCenterId的包装类
    @Bean
    public WorkAndDataCenterIdHandler workAndDataCenterIdHandler(StringRedisTemplate stringRedisTemplate){
        return new WorkAndDataCenterIdHandler(stringRedisTemplate);
    }
    
    @Bean
    public WorkDataCenterId workDataCenterId(WorkAndDataCenterIdHandler workAndDataCenterIdHandler){
        return workAndDataCenterIdHandler.getWorkAndDataCenterId();
    }
    
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(WorkDataCenterId workDataCenterId){
        return new SnowflakeIdGenerator(workDataCenterId);
    }
}
