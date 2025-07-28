package com.damai.shardingsphere;

import cn.hutool.core.collection.CollectionUtil;
import com.damai.enums.BaseCode;
import com.damai.exception.DaMaiFrameException;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
/**
 * @program: 极度真实还原大麦网高并发实战项目。 添加 阿星不是程序员 微信，添加时备注 大麦 来获取项目的完整资料 
 * @description: 订单分表
 * @author: 阿星不是程序员
 **/
public class TableOrderComplexGeneArithmetic implements ComplexKeysShardingAlgorithm<Long> {
    
    //属性分表名
    private static final String SHARDING_COUNT_KEY_NAME = "sharding-count";
    
    private int shardingCount;
    
    @Override
    public void init(Properties props) {
        shardingCount = Integer.parseInt(props.getProperty(SHARDING_COUNT_KEY_NAME));
    }
    //ComplexKeysShardingValue,是ShardingSphere复合分片值的封装类，用于传递多个分片键的查询条件
    @Override
    public Collection<String> doSharding(Collection<String> allActualSplitTableNames, ComplexKeysShardingValue<Long> complexKeysShardingValue) {
        /*
        * logicTableName = "d_order"
columnNameAndShardingValuesMap = {
    "order_number": [123456],
    "user_id": [10001]
}*/
        List<String> actualTableNames = new ArrayList<>(allActualSplitTableNames.size());
        String logicTableName = complexKeysShardingValue.getLogicTableName();
        Map<String, Collection<Long>> columnNameAndShardingValuesMap = complexKeysShardingValue.getColumnNameAndShardingValuesMap();
        if (CollectionUtil.isEmpty(columnNameAndShardingValuesMap)) {
            //表示需要查询所有的表
            return allActualSplitTableNames;
        }
        Collection<Long> orderNumberValues = columnNameAndShardingValuesMap.get("order_number");
        Collection<Long> userIdValues = columnNameAndShardingValuesMap.get("user_id");
        //分片键的值
        Long value = null;
        if (CollectionUtil.isNotEmpty(orderNumberValues)) {
            value = orderNumberValues.stream().findFirst().orElseThrow(() -> new DaMaiFrameException(BaseCode.ORDER_NUMBER_NOT_EXIST));
        } else if (CollectionUtil.isNotEmpty(userIdValues)) {
            value = userIdValues.stream().findFirst().orElseThrow(() -> new DaMaiFrameException(BaseCode.USER_ID_NOT_EXIST));
        }
        //如果order_number或者user_id的值存在
        if (Objects.nonNull(value)) {
            //用取到的值对shardingCout取模，经过分片基因法替换后订单编号取模得到的值，和使用用户Id取值后的值是相同的
            actualTableNames.add(logicTableName + "_" + ((shardingCount - 1) & value));
            return actualTableNames;
        }
        //如果order_number和user_id的值都不存在，那么就查询所有的表
        return allActualSplitTableNames;
    }
}
