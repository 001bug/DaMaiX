package com.damai.service;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.DateUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baidu.fsg.uid.UidGenerator;
import com.damai.core.RedisKeyManage;
import com.damai.util.StringUtil;
import com.damai.dto.ApiDataDto;
import com.damai.enums.ApiRuleType;
import com.damai.enums.BaseCode;
import com.damai.enums.RuleTimeUnit;
import com.damai.exception.DaMaiFrameException;
import com.damai.kafka.ApiDataMessageSend;
import com.damai.property.GatewayProperty;
import com.damai.redis.RedisCache;
import com.damai.redis.RedisKeyBuild;
import com.damai.service.lua.ApiRestrictCacheOperate;
import com.damai.util.DateUtils;
import com.damai.vo.DepthRuleVo;
import com.damai.vo.RuleVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @program: 极度真实还原大麦网高并发实战项目。 添加 阿星不是程序员 微信，添加时备注 大麦 来获取项目的完整资料 
 * @description: 接口请求记录
 * @author: 阿星不是程序员
 **/
@Slf4j
@Component
public class ApiRestrictService {
    
    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private GatewayProperty gatewayProperty;
    
    @Autowired(required = false)
    private ApiDataMessageSend apiDataMessageSend;
    
    @Autowired
    private ApiRestrictCacheOperate apiRestrictCacheOperate;
    
    @Autowired
    private UidGenerator uidGenerator;
    
    public boolean checkApiRestrict(String requestUri){
        if (gatewayProperty.getApiRestrictPaths() != null) {
            for (String apiRestrictPath : gatewayProperty.getApiRestrictPaths()) {
                PathMatcher matcher = new AntPathMatcher();
                if(matcher.match(apiRestrictPath, requestUri)){
                    return true;
                }
            }
        }
        return false;
    }
    
    public void apiRestrict(String id, String url, ServerHttpRequest request) {
        if (checkApiRestrict(url)) {
            long triggerResult = 0L;
            long triggerCallStat = 0L;
            long apiCount;
            long threshold;
            long messageIndex;
            String message = "";
            //获取请求客户端的ip
            String ip = getIpAddress(request);
            
            StringBuilder stringBuilder = new StringBuilder(ip);
            if (StringUtil.isNotEmpty(id)) {
                stringBuilder.append("_").append(id);
            }
            String commonKey = stringBuilder.append("_").append(url).toString();
            try {
                List<DepthRuleVo> depthRuleVoList = new ArrayList<>();
                //查询规则 Hash结构
                //普通规则,redisCache.getForHash -从Redis Hash结构中获取数据
                //RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH) -构建Redis主键名称
                //RedisKeyBuild.createRedisKey(RedisKeyManage.RULE).getRelKey() -构建Redis子键名称
                /*
                all_rule_hash: {
                    "rule": "{\"id\":\"123\",\"threshold\":100,\"message\":\"限流提示\"...}",
                    "depth_rule": "[{...},{...}]"
                }
                * */
                RuleVo ruleVo = redisCache.getForHash(RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH), RedisKeyBuild.createRedisKey(RedisKeyManage.RULE).getRelKey(),RuleVo.class);
                //深度规则
                String depthRuleStr = redisCache.getForHash(RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH), RedisKeyBuild.createRedisKey(RedisKeyManage.DEPTH_RULE).getRelKey(),String.class);
                if (StringUtil.isNotEmpty(depthRuleStr)) {
                    depthRuleVoList = JSON.parseArray(depthRuleStr,DepthRuleVo.class);
                }
                //规则类型 0:不存在 1: 普通规则 2:深度规则
                int apiRuleType = ApiRuleType.NO_RULE.getCode();
                //判断ruleVo是否为空.
                if (Optional.ofNullable(ruleVo).isPresent()) {
                    apiRuleType = ApiRuleType.RULE.getCode();
                    message = ruleVo.getMessage();
                }
                if (Optional.ofNullable(ruleVo).isPresent() && CollectionUtil.isNotEmpty(depthRuleVoList)) {
                    apiRuleType = ApiRuleType.DEPTH_RULE.getCode();
                }
                if (apiRuleType == ApiRuleType.RULE.getCode() || apiRuleType == ApiRuleType.DEPTH_RULE.getCode()) {
                    //断言关键字,要求ruleVo不能为空
                    assert ruleVo != null;
                    //普通规则构建,这个JSON对象会传递给Lua脚本进行限流计算
                    JSONObject parameter = getRuleParameter(apiRuleType,commonKey,ruleVo);
                    
                    if (apiRuleType == ApiRuleType.DEPTH_RULE.getCode()) {
                        //深度规则构建
                        parameter = getDepthRuleParameter(parameter,commonKey,depthRuleVoList);
                    }
                    //执行Lua脚本
                    ApiRestrictData apiRestrictData = apiRestrictCacheOperate
                            .apiRuleOperate(Collections.singletonList(JSON.toJSONString(parameter)), new Object[]{});
                    //是否需要进行限制
                    triggerResult = apiRestrictData.getTriggerResult();
                    //是否进行保存记录
                    triggerCallStat = apiRestrictData.getTriggerCallStat();
                    //请求数
                    apiCount = apiRestrictData.getApiCount();
                    //规则阈值
                    threshold = apiRestrictData.getThreshold();
                    //定制规则提示语
                    messageIndex = apiRestrictData.getMessageIndex();
                    if (messageIndex != -1) {
                        message = Optional.ofNullable(depthRuleVoList.get((int)messageIndex))
                                .map(DepthRuleVo::getMessage)
                                .filter(StringUtil::isNotEmpty)
                                .orElse(message);
                    }
                    log.info("api rule [key : {}], [triggerResult : {}], [triggerCallStat : {}], [apiCount : {}], [threshold : {}]",commonKey,triggerResult,triggerCallStat,apiCount,threshold);
                }
            }catch (Exception e) {
                log.error("redis Lua eror", e);
            }
            if (triggerResult == 1) {
                if (triggerCallStat == ApiRuleType.RULE.getCode() || triggerCallStat == ApiRuleType.DEPTH_RULE.getCode()) {
                    saveApiData(request, url, (int)triggerCallStat);
                }
                String defaultMessage = BaseCode.API_RULE_TRIGGER.getMsg();
                if (StringUtil.isNotEmpty(message)) {
                    defaultMessage = message;
                }
                throw new DaMaiFrameException(BaseCode.API_RULE_TRIGGER.getCode(),defaultMessage);
            }
        }
    }
    /*
    *普通规则构建
    * */
    public JSONObject getRuleParameter(int apiRuleType, String commonKey, RuleVo ruleVo){
        JSONObject parameter = new JSONObject();
        
        parameter.put("apiRuleType",apiRuleType);
        //普通规则中要进行统计请求数
        String ruleKey = "rule_api_limit" + "_" + commonKey;
        parameter.put("ruleKey",ruleKey);
        //普通规则中进行统计的时间
        parameter.put("statTime",String.valueOf(Objects.equals(ruleVo.getStatTimeType(), RuleTimeUnit.SECOND.getCode()) ? ruleVo.getStatTime() : ruleVo.getStatTime() * 60));
        //普通规则中进行统计的阈值
        parameter.put("threshold",ruleVo.getThreshold());
        //普通规则超过阈值后限制的时间
        parameter.put("effectiveTime",String.valueOf(Objects.equals(ruleVo.getEffectiveTimeType(), RuleTimeUnit.SECOND.getCode()) ? ruleVo.getEffectiveTime() : ruleVo.getEffectiveTime() * 60));
        //实现普通规则执行限制
        parameter.put("ruleLimitKey", RedisKeyBuild.createRedisKey(RedisKeyManage.RULE_LIMIT,commonKey).getRelKey());
        //进行统计超过普通规则的数量sorted set结构
        parameter.put("zSetRuleStatKey", RedisKeyBuild.createRedisKey(RedisKeyManage.Z_SET_RULE_STAT,commonKey).getRelKey());
        
        return parameter;
    }
    /*
    * 深度规则构建
    * */
    public JSONObject getDepthRuleParameter(JSONObject parameter,String commonKey,List<DepthRuleVo> depthRuleVoList){
        //按照时间窗口排序,确保规则按书剑顺序执行
        depthRuleVoList = sortStartTimeWindow(depthRuleVoList);
        //深度规则的数量
        parameter.put("depthRuleSize",String.valueOf(depthRuleVoList.size()));
        //当前时间戳
        parameter.put("currentTime",System.currentTimeMillis());
        
        List<JSONObject> depthRules = new ArrayList<>();
        for (int i = 0; i < depthRuleVoList.size(); i++) {
            JSONObject depthRule = new JSONObject();
            DepthRuleVo depthRuleVo = depthRuleVoList.get(i);
            //深度规则中进行统计的时间
            depthRule.put("statTime",Objects.equals(depthRuleVo.getStatTimeType(), RuleTimeUnit.SECOND.getCode()) ? depthRuleVo.getStatTime() : depthRuleVo.getStatTime() * 60);
            //深度规则中进行统计的阈值
            depthRule.put("threshold",depthRuleVo.getThreshold());
            //生效时间
            depthRule.put("effectiveTime",String.valueOf(Objects.equals(depthRuleVo.getEffectiveTimeType(), RuleTimeUnit.SECOND.getCode()) ? depthRuleVo.getEffectiveTime() : depthRuleVo.getEffectiveTime() * 60));
            //深度规则限制键
            depthRule.put("depthRuleLimit", RedisKeyBuild.createRedisKey(RedisKeyManage.DEPTH_RULE_LIMIT,i,commonKey).getRelKey());
            //时间窗口时间戳
            depthRule.put("startTimeWindowTimestamp",depthRuleVo.getStartTimeWindowTimestamp());
            depthRule.put("endTimeWindowTimestamp",depthRuleVo.getEndTimeWindowTimestamp());
            
            depthRules.add(depthRule);
        }
        
        parameter.put("depthRules",depthRules);
        
        return parameter;
    }
    
    public List<DepthRuleVo> sortStartTimeWindow(List<DepthRuleVo> depthRuleVoList){
        return depthRuleVoList.stream().peek(depthRuleVo -> {
            depthRuleVo.setStartTimeWindowTimestamp(getTimeWindowTimestamp(depthRuleVo.getStartTimeWindow()));
            depthRuleVo.setEndTimeWindowTimestamp((getTimeWindowTimestamp(depthRuleVo.getEndTimeWindow())));
        }).sorted(Comparator.comparing(DepthRuleVo::getStartTimeWindowTimestamp)).collect(Collectors.toList());
    }
    
    public long getTimeWindowTimestamp(String timeWindow){
        String today = DateUtil.today();
        return DateUtil.parse(today + " " + timeWindow).getTime();
    }
    
    /**
      * 获取请求的归属IP地址
      *
      * @param request 请求
      */
    public static String getIpAddress(ServerHttpRequest request) {
        String unknown = "unknown";
        String split = ",";
        HttpHeaders headers = request.getHeaders();
        String ip = headers.getFirst("x-forwarded-for");
        if (ip != null && ip.length() != 0 && !unknown.equalsIgnoreCase(ip)) {
            // 多次反向代理后会有多个ip值，第一个ip才是真实ip
            if (ip.contains(split)) {
                ip = ip.split(split)[0];
            }
        }
        if (ip == null || ip.length() == 0 || unknown.equalsIgnoreCase(ip)) {
            ip = headers.getFirst("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || unknown.equalsIgnoreCase(ip)) {
            ip = headers.getFirst("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || unknown.equalsIgnoreCase(ip)) {
            ip = headers.getFirst("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.length() == 0 || unknown.equalsIgnoreCase(ip)) {
            ip = headers.getFirst("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.length() == 0 || unknown.equalsIgnoreCase(ip)) {
            ip = headers.getFirst("X-Real-IP");
        }
        if (ip == null || ip.length() == 0 || unknown.equalsIgnoreCase(ip)) {
            ip = Objects.requireNonNull(request.getRemoteAddress()).getAddress().getHostAddress();
        }
        return ip;
    }
    
    public void saveApiData(ServerHttpRequest request, String apiUrl, Integer type){
        ApiDataDto apiDataDto = new ApiDataDto();
        //id
        apiDataDto.setId(uidGenerator.getUid());
        //客户端ip
        apiDataDto.setApiAddress(getIpAddress(request));
        //请求的路劲
        apiDataDto.setApiUrl(apiUrl);
        //创建的时间
        apiDataDto.setCreateTime(DateUtils.now());
        //按天维度记录请求时间
        apiDataDto.setCallDayTime(DateUtils.nowStr(DateUtils.FORMAT_DATE));
        //按小时维度记录请求时间
        apiDataDto.setCallHourTime(DateUtils.nowStr(DateUtils.FORMAT_HOUR));
        //按分钟维度记录请求时间
        apiDataDto.setCallMinuteTime(DateUtils.nowStr(DateUtils.FORMAT_MINUTE));
        //按秒维度记录请求时间
        apiDataDto.setCallSecondTime(DateUtils.nowStr(DateUtils.FORMAT_SECOND));
        //api规则生效类型 1普通规则 2深度规则
        apiDataDto.setType(type);
        Optional.ofNullable(apiDataMessageSend).ifPresent(send -> send.sendMessage(JSON.toJSONString(apiDataDto)));
    }
}
