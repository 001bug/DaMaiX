package com.damai.lockinfo;


import com.damai.core.SpringUtil;
import com.damai.parser.ExtParameterNameDiscoverer;
import com.damai.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.util.ObjectUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static com.damai.core.Constants.SEPARATOR;

/**
 * @program: 极度真实还原大麦网高并发实战项目。 添加 阿星不是程序员 微信，添加时备注 大麦 来获取项目的完整资料 
 * @description: 锁信息抽象, 在整个锁机制中起到了标识和分区的重要作用
 * @author: 阿星不是程序员
 **/
@Slf4j
public abstract class AbstractLockInfoHandle implements LockInfoHandle {
    
    private static final String LOCK_DISTRIBUTE_ID_NAME_PREFIX = "LOCK_DISTRIBUTE_ID";

    private final ParameterNameDiscoverer nameDiscoverer = new ExtParameterNameDiscoverer();

    private final ExpressionParser parser = new SpelExpressionParser();
    
    /**
     * 锁信息前缀
     * @return 具体前缀
     * */
    protected abstract String getLockPrefixName();
    @Override
    public String getLockName(JoinPoint joinPoint,String name,String[] keys){
        //最终锁名 = 应用前缀 + "-" + "REPEAT_EXECUTE_LIMIT" + ":" + 业务名 + ":" + 动态key
        return SpringUtil.getPrefixDistinctionName() + "-" + getLockPrefixName() + SEPARATOR + name + getRelKey(joinPoint, keys);
    }
    /**
     * 解析出锁的键
     * @param name 业务名
     * @param keys 参数名
     * @return 解析后的锁的键
     * */
    @Override
    public String simpleGetLockName(String name,String[] keys){
        List<String> definitionKeyList = new ArrayList<>();
        for (String key : keys) {
            if (StringUtil.isNotEmpty(key)) {
                definitionKeyList.add(key);
            }
        }
        return SpringUtil.getPrefixDistinctionName() + "-" + 
                LOCK_DISTRIBUTE_ID_NAME_PREFIX + SEPARATOR + name + SEPARATOR + String.join(SEPARATOR, definitionKeyList);
    }

    /**
     * 获取自定义键
     * @param joinPoint 切面
     * @param keys 键
     * @return 键
     * */
    private String getRelKey(JoinPoint joinPoint, String[] keys){
        Method method = getMethod(joinPoint);
        List<String> definitionKeys = getSpElKey(keys, method, joinPoint.getArgs());
        return SEPARATOR + String.join(SEPARATOR, definitionKeys);
    }
    /**
     * 获取自定义键
     * @param joinPoint 切点
     * @return 切点的方法
     * */
    //获取真正的实现方法, 解决Spring AOP中接口代理与实现方法映射
    private Method getMethod(JoinPoint joinPoint) {
        //获取方法签名
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        //判断是否为接口方法
        if (method.getDeclaringClass().isInterface()) {
            try {

                method = joinPoint.getTarget().getClass()//获取目标对象的实际类型
                        .getDeclaredMethod(//获取声明的方法
                                signature.getName(),//获取方法名
                        method.getParameterTypes());//方法参数类型数组
            } catch (Exception e) {
                log.error("get method error ",e);
            }
        }
        return method;
    }
    /*
    * */
    private List<String> getSpElKey(String[] definitionKeys, Method method, Object[] parameterValues) {
        List<String> definitionKeyList = new ArrayList<>();
        for (String definitionKey : definitionKeys) {
            if (!ObjectUtils.isEmpty(definitionKey)) {
                EvaluationContext context = new MethodBasedEvaluationContext(null, method, parameterValues, nameDiscoverer);
                Object objKey = parser.parseExpression(definitionKey).getValue(context);
                definitionKeyList.add(ObjectUtils.nullSafeToString(objKey));
            }
        }
        return definitionKeyList;
    }

}
