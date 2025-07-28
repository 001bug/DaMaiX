package com.damai.shardingsphere;

import cn.hutool.core.collection.CollectionUtil;
import com.damai.enums.BaseCode;
import com.damai.exception.DaMaiFrameException;
import com.damai.util.StringUtil;
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
 * @description: 订单分库
 * @author: 阿星不是程序员
 **/
public class DatabaseOrderComplexGeneArithmetic implements ComplexKeysShardingAlgorithm<Long> {
    //属性分库名
    private static final String SHARDING_COUNT_KEY_NAME = "sharding-count";
    //属性分表名
    private static final String TABLE_SHARDING_COUNT_KEY_NAME = "table-sharding-count";
    //分库数量
    private int shardingCount;
    //分表数量
    private int tableShardingCount;
    
    @Override
    public void init(Properties props) {
        this.shardingCount = Integer.parseInt(props.getProperty(SHARDING_COUNT_KEY_NAME));
        this.tableShardingCount = Integer.parseInt(props.getProperty(TABLE_SHARDING_COUNT_KEY_NAME));
    }
    @Override
    public Collection<String> doSharding(Collection<String> allActualSplitDatabaseNames, ComplexKeysShardingValue<Long> complexKeysShardingValue) {
        //allActualSplitDatabaseNames是所有真实库
        List<String> actualDatabaseNames = new ArrayList<>(allActualSplitDatabaseNames.size());
        Map<String, Collection<Long>> columnNameAndShardingValuesMap = complexKeysShardingValue.getColumnNameAndShardingValuesMap();
        if (CollectionUtil.isEmpty(columnNameAndShardingValuesMap)) {
            return actualDatabaseNames;
        }
        
        Collection<Long> orderNumberValues = columnNameAndShardingValuesMap.get("order_number");
        Collection<Long> userIdValues = columnNameAndShardingValuesMap.get("user_id");
        
        Long value = null;
        if (CollectionUtil.isNotEmpty(orderNumberValues)) {
            value = orderNumberValues.stream().findFirst().orElseThrow(() -> new DaMaiFrameException(BaseCode.ORDER_NUMBER_NOT_EXIST));
        } else if (CollectionUtil.isNotEmpty(userIdValues)) {
            value = userIdValues.stream().findFirst().orElseThrow(() -> new DaMaiFrameException(BaseCode.USER_ID_NOT_EXIST));
        }
        //如果order_number或者user_id的值存在,以下也是重要代码部分
        if (Objects.nonNull(value)) {
            //获得值后再获得实际的分库的索引
            long databaseIndex = calculateDatabaseIndex(shardingCount,value,tableShardingCount);
            String databaseIndexStr = String.valueOf(databaseIndex);
            for (String actualSplitDatabaseName : allActualSplitDatabaseNames) {
                //将所有的分库名和得到的分库索引进行匹配
                if (actualSplitDatabaseName.contains(databaseIndexStr)) {
                    actualDatabaseNames.add(actualSplitDatabaseName);
                    break;
                }
            }
            return actualDatabaseNames;
        }else {
            //如果没有分片键查询，则把所有真实库返回
            return allActualSplitDatabaseNames;
        }
    }
    
    /**
     * 计算给定表索引应分配到的数据库编号。
     *
     * @param databaseCount 数据库总数
     * @param splicingKey    分片键
     * @param tableCount    表总数
     * @return 分配到的数据库编号
     */
    public long calculateDatabaseIndex(Integer databaseCount, Long splicingKey, Integer tableCount) {
        String splicingKeyBinary = Long.toBinaryString(splicingKey);
        long replacementLength = log2N(tableCount);
        String geneBinaryStr = splicingKeyBinary.substring(splicingKeyBinary.length() - (int) replacementLength);
        
        if (StringUtil.isNotEmpty(geneBinaryStr)) {
            int h;
            int geneOptimizeHashCode = (h = geneBinaryStr.hashCode()) ^ (h >>> 16);
            return (databaseCount - 1) & geneOptimizeHashCode;
        }
        throw new DaMaiFrameException(BaseCode.NOT_FOUND_GENE);
    }
    
    public long log2N(long count) {
        return (long)(Math.log(count)/ Math.log(2));
    }
}
