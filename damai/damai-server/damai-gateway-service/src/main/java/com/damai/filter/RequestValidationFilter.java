package com.damai.filter;


import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.baidu.fsg.uid.UidGenerator;
import com.damai.conf.RequestTemporaryWrapper;
import com.damai.enums.BaseCode;
import com.damai.exception.ArgumentError;
import com.damai.exception.ArgumentException;
import com.damai.exception.DaMaiFrameException;
import com.damai.pro.limit.RateLimiter;
import com.damai.pro.limit.RateLimiterProperty;
import com.damai.property.GatewayProperty;
import com.damai.service.ApiRestrictService;
import com.damai.service.ChannelDataService;
import com.damai.service.TokenService;
import com.damai.threadlocal.BaseParameterHolder;
import com.damai.util.RsaSignTool;
import com.damai.util.RsaTool;
import com.damai.util.StringUtil;
import com.damai.vo.GetChannelDataVo;
import com.damai.vo.UserVo;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static com.damai.constant.Constant.GRAY_PARAMETER;
import static com.damai.constant.Constant.TRACE_ID;
import static com.damai.constant.GatewayConstant.BUSINESS_BODY;
import static com.damai.constant.GatewayConstant.CODE;
import static com.damai.constant.GatewayConstant.ENCRYPT;
import static com.damai.constant.GatewayConstant.NO_VERIFY;
import static com.damai.constant.GatewayConstant.REQUEST_BODY;
import static com.damai.constant.GatewayConstant.TOKEN;
import static com.damai.constant.GatewayConstant.USER_ID;
import static com.damai.constant.GatewayConstant.V2;
import static com.damai.constant.GatewayConstant.VERIFY_VALUE;

/**
 * @program: 极度真实还原大麦网高并发实战项目。 添加 阿星不是程序员 微信，添加时备注 大麦 来获取项目的完整资料 
 * @description: 请求过滤器
 * @author: 阿星不是程序员
 **/

@Component
@Slf4j
public class RequestValidationFilter implements GlobalFilter, Ordered {

    @Autowired
    private ServerCodecConfigurer serverCodecConfigurer;

    @Autowired
    private ChannelDataService channelDataService;

    @Autowired
    private ApiRestrictService apiRestrictService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private GatewayProperty gatewayProperty;
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Autowired
    private RateLimiterProperty rateLimiterProperty;
    
    @Autowired
    private RateLimiter rateLimiter;
    

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        //防刷限流
        if (rateLimiterProperty.getRateSwitch()) {
            try {
                rateLimiter.acquire();
                return doFilter(exchange,chain);
            } catch (InterruptedException e) {
                log.error("interrupted error",e);
                throw new DaMaiFrameException(BaseCode.THREAD_INTERRUPTED);
            } finally {
                rateLimiter.release();
            }
        }else{
            return doFilter(exchange, chain);
        }
    }
    
    public Mono<Void> doFilter(final ServerWebExchange exchange, final GatewayFilterChain chain){
        //获得请求
        ServerHttpRequest request = exchange.getRequest();
        //链路id
        String traceId = request.getHeaders().getFirst(TRACE_ID);
        //灰度标识
        String gray = request.getHeaders().getFirst(GRAY_PARAMETER);
        //是否验证参数
        String noVerify = request.getHeaders().getFirst(NO_VERIFY);
        //如果链路id不存在,那么在这里生成
        if (StringUtil.isEmpty(traceId)) {
            traceId = String.valueOf(uidGenerator.getUid());
        }
        //将链路id放到日志的MDC中便于日志输出
        MDC.put(TRACE_ID,traceId);
        Map<String,String> headMap = new HashMap<>(8);
        headMap.put(TRACE_ID,traceId);
        headMap.put(GRAY_PARAMETER,gray);
        if (StringUtil.isNotEmpty(noVerify)) {
            headMap.put(NO_VERIFY,noVerify);
        }
        //将链路id放入Threadload中
        BaseParameterHolder.setParameter(TRACE_ID,traceId);
        //将灰度标识放入Threadload中
        BaseParameterHolder.setParameter(GRAY_PARAMETER,gray);
        //获得请求类型
        MediaType contentType = request.getHeaders().getContentType();
        //application json请求
        if (Objects.nonNull(contentType) && contentType.toString().toLowerCase().contains(MediaType.APPLICATION_JSON_VALUE.toLowerCase())) {
            return readBody(exchange,chain,headMap);
        }else {
            Map<String, String> map = doExecute("", exchange);
            map.remove(REQUEST_BODY);
            map.putAll(headMap);
            request.mutate().headers(httpHeaders -> {
                map.forEach(httpHeaders::add);
            });
            return chain.filter(exchange);
        }
    } 
    //对请求进行参数校验
    private Mono<Void> readBody(ServerWebExchange exchange, GatewayFilterChain chain, Map<String,String> headMap){
        log.info("current thread readBody : {}",Thread.currentThread().getName());
        RequestTemporaryWrapper requestTemporaryWrapper = new RequestTemporaryWrapper();
        
        ServerRequest serverRequest = ServerRequest.create(exchange, serverCodecConfigurer.getReaders());
        Mono<String> modifiedBody = serverRequest
                .bodyToMono(String.class)//读取请求体->Mono<String>
                //execute是执行参数校验的方法
                .flatMap(originalBody ->//对每个请求体进行处理
                        Mono.just(execute(//包装同步方法为响应式
                                requestTemporaryWrapper,//传入临时包装器
                                originalBody,//传入原始请求体
                                exchange)))//传入交换对象
                .switchIfEmpty(Mono.defer(() -> Mono.just(execute(requestTemporaryWrapper,"",exchange))));//处理空请求体情况
        
        BodyInserter bodyInserter = BodyInserters.fromPublisher(modifiedBody, String.class);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(exchange.getRequest().getHeaders());
        headers.remove(HttpHeaders.CONTENT_LENGTH);
        
        CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange, headers);
        return bodyInserter
                .insert(outputMessage, new BodyInserterContext())
                .then(Mono.defer(() -> chain.filter(
                        exchange.mutate().request(decorateHead(exchange, headers, outputMessage, requestTemporaryWrapper, headMap)).build()
                )))
                .onErrorResume((Function<Throwable, Mono<Void>>) throwable -> Mono.error(throwable));
    }
    
    public String execute(RequestTemporaryWrapper requestTemporaryWrapper,String requestBody,ServerWebExchange exchange){
        //进行业务验证，并将相关参数放入map
        Map<String, String> map = doExecute(requestBody, exchange);//获取处理后的请求体
        //这里的map中的数据在doExecute中放入,有修改后的请求体和要放在请求头重的数据,先拿出请求体用来返回,然后从map中移除
        String body = map.get(REQUEST_BODY);
        map.remove(REQUEST_BODY);//从Map中移除,避免放入请求头
        requestTemporaryWrapper.setMap(map);//剩余参数存储到临时包装器,传递给下游的请求头
        return body;
    }
    /*
    * 具体进行参数验证的逻辑
    * */
    private Map<String,String> doExecute(String originalBody,ServerWebExchange exchange){
        log.info("current thread verify: {}",Thread.currentThread().getName());

        ServerHttpRequest request = exchange.getRequest();
        //得到请求体
        String requestBody = originalBody;
        Map<String, String> bodyContent = new HashMap<>(32);
        if (StringUtil.isNotEmpty(originalBody)) {
            //请求体转化为map结构
            bodyContent = JSON.parseObject(originalBody, Map.class);
        }
        //基础参数code渠道,微信,QQ等
        String code = null;
        //用户的token
        String token;
        //用户id
        String userId = null;
        //请求路径
        String url = request.getPath().value();
        //是否需要验证
        String noVerify = request.getHeaders().getFirst(NO_VERIFY);
        //是否允许跳过参数验证
        boolean allowNormalAccess = gatewayProperty.isAllowNormalAccess();
        if ((!allowNormalAccess) && (VERIFY_VALUE.equals(noVerify))) {
            throw new DaMaiFrameException(BaseCode.ONLY_SIGNATURE_ACCESS_IS_ALLOWED);
        }
        if (checkParameter(originalBody,noVerify) && !skipCheckParameter(url)) {

            String encrypt = request.getHeaders().getFirst(ENCRYPT);
            //应用渠道
            code = bodyContent.get(CODE);
            //token
            token = request.getHeaders().getFirst(TOKEN);
            //验证code参数并获取基础参数
            GetChannelDataVo channelDataVo = channelDataService.getChannelDataByCode(code);
            //如果v2版本就要先对参数进行解密
            if (StringUtil.isNotEmpty(encrypt) && V2.equals(encrypt)) {
                //使用rsa参数私钥进行解密
                String decrypt = RsaTool.decrypt(bodyContent.get(BUSINESS_BODY),channelDataVo.getDataSecretKey());
                //把加密的请求体替换成解密后的请求体
                bodyContent.put(BUSINESS_BODY,decrypt);
            }
            //验证签名,看是否被篡改过
            boolean checkFlag = RsaSignTool.verifyRsaSign256(bodyContent, channelDataVo.getSignPublicKey());
            if (!checkFlag) {
                throw new DaMaiFrameException(BaseCode.RSA_SIGN_ERROR);
            }
            //查看白名单,决定是否要跳过token验证
            //默认注册和登录接口跳过验证
            boolean skipCheckTokenResult = skipCheckToken(url);
            if (!skipCheckTokenResult && StringUtil.isEmpty(token)) {
                ArgumentError argumentError = new ArgumentError();
                argumentError.setArgumentName(token);
                argumentError.setMessage("token参数为空");
                List<ArgumentError> argumentErrorList = new ArrayList<>();
                argumentErrorList.add(argumentError);
                throw new ArgumentException(BaseCode.ARGUMENT_EMPTY.getCode(),argumentErrorList);
            }
            //获取用户id
            if (!skipCheckTokenResult) {
                UserVo userVo = tokenService.getUser(token,code,channelDataVo.getTokenSecret());
                userId = userVo.getId();
            }
            
            requestBody = bodyContent.get(BUSINESS_BODY);
        }
        //根据规则对api接口进行防刷限制
        apiRestrictService.apiRestrict(userId,url,request);
        //将修改后的请求体和要传递的请求头参数放入map中
        Map<String,String> map = new HashMap<>(4);
        map.put(REQUEST_BODY,requestBody);
        if (StringUtil.isNotEmpty(code)) {
            map.put(CODE,code);
        }
        if (StringUtil.isNotEmpty(userId)) {
            map.put(USER_ID,userId);
        }
        return map;
    }
    /**
     * 将网关层request请求头中的重要参数传递给后续的微服务中
     */
    private ServerHttpRequestDecorator decorateHead(ServerWebExchange exchange, HttpHeaders headers, CachedBodyOutputMessage outputMessage, RequestTemporaryWrapper requestTemporaryWrapper, Map<String,String> headMap){
        return new ServerHttpRequestDecorator(exchange.getRequest()){
            @Override
            public HttpHeaders getHeaders() {
                log.info("current thread getHeaders: {}",Thread.currentThread().getName());
                long contentLength = headers.getContentLength();
                HttpHeaders newHeaders = new HttpHeaders();
                newHeaders.putAll(headers);
                Map<String, String> map = requestTemporaryWrapper.getMap();
                if (CollectionUtil.isNotEmpty(map)) {
                    newHeaders.setAll(map);
                }
                if (CollectionUtil.isNotEmpty(headMap)) {
                    newHeaders.setAll(headMap);
                }
                if (contentLength > 0){
                    newHeaders.setContentLength(contentLength);
                }else {
                    newHeaders.set(HttpHeaders.TRANSFER_ENCODING,"chunked");
                }
                if (CollectionUtil.isNotEmpty(headMap) && StringUtil.isNotEmpty(headMap.get(TRACE_ID))) {
                    MDC.put(TRACE_ID,headMap.get(TRACE_ID));
                }
                return newHeaders;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                return outputMessage.getBody();
            }
        };
    }
    /*指定执行顺序*/
    @Override
    public int getOrder() {
        return -2;
    }
    /*验证是否跳过token验证*/
    public boolean skipCheckToken(String url){
        for (String skipCheckTokenPath : gatewayProperty.getCheckTokenPaths()) {
            //Spring框架中的一个接口,用于路径匹配模式的处理.
            PathMatcher matcher = new AntPathMatcher();
            if (matcher.match(skipCheckTokenPath, url)) {
                return false;
            }
        }
        return true;
    }
    /*验证是否跳过参数验证*/
    public boolean skipCheckParameter(String url){
        for (String skipCheckTokenPath : gatewayProperty.getCheckSkipParmeterPaths()) {
            PathMatcher matcher = new AntPathMatcher();
            if (matcher.match(skipCheckTokenPath, url)) {
                return true;
            }
        }
        return false;
    }
    /*
    * 验证请求头的参数noVerfy=true
    * */
    public boolean checkParameter(String originalBody,String noVerify){
        return (!(VERIFY_VALUE.equals(noVerify))) && StringUtil.isNotEmpty(originalBody);
    }
}
