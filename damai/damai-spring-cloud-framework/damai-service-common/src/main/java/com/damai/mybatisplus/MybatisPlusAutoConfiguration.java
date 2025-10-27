package com.damai.mybatisplus;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
/**
 * @program: 极度真实还原大麦网高并发实战项目。 添加 阿星不是程序员 微信，添加时备注 大麦 来获取项目的完整资料 
 * @description: mybatisPlus配置
 * @author: 阿星不是程序员
 **/
public class MybatisPlusAutoConfiguration {
    
    /**
     * 必须字段自动填充,例如创建时间，编辑时间.比如BaseDataTable中的createTime,editTime会自动填充
     * */
    @Bean
    public MetaObjectHandler metaObjectHandler(){
        return new MybatisPlusMetaObjectHandler();
    }
    
    /**
     * 分页插件
     * 注册 MyBatis-Plus 的 分页拦截器，
     * 让你在写 SQL 查询时，只要调用 .page() 方法，
     * MyBatis-Plus 自动帮你拼接 LIMIT、OFFSET 等分页语句。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
