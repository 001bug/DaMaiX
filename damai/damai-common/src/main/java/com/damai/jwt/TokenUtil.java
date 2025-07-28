package com.damai.jwt;

import com.alibaba.fastjson.JSONObject;
import com.damai.enums.BaseCode;
import com.damai.exception.DaMaiFrameException;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;

/**
 * @program: 极度真实还原大麦网高并发实战项目。 添加 阿星不是程序员 微信，添加时备注 大麦 来获取项目的完整资料 
 * @description: token工具
 * @author: 阿星不是程序员
 **/
@Slf4j
public class TokenUtil {
    //指定签名的时候使用的签名算法
    private static final SignatureAlgorithm SIGNATURE_ALGORITHM = SignatureAlgorithm.HS256;
    /**
     * 用户登录成功后生成Jwt
     * 使用Hs256算法
     *
     * @param id        标识
     * @param info      登录成功的user对象
     * @param ttlMillis jwt过期时间
     * @param tokenSecret 私钥
     * @return
     */
    public static String createToken(String id, String info, long ttlMillis, String tokenSecret) {
        //生成JWT的时间
        long nowMillis = System.currentTimeMillis();
        
        //创建一个JwtBuilder,设置jwt的body(payload)
        JwtBuilder builder = Jwts.builder()
//                .setClaims(claims)
                //设置Jwt的唯一标识
                .setId(id)
                //iat: jwt的签发时间
                .setIssuedAt(new Date(nowMillis))
                //sub(Subject)：代表这个JWT的主体，即它的所有人，这个是一个json格式的字符串，可以存放什么userid，roldid之类的，作为什么用户的唯一标志
                .setSubject(info)
                //生成签名
                .signWith(SIGNATURE_ALGORITHM, tokenSecret);
        if (ttlMillis >= 0) {
            //设置过期时间
            builder.setExpiration(new Date(nowMillis + ttlMillis));
        }
        return builder.compact();
    }
    /**
     * Token的解密
     *
     * @param token 加密后的token
     * @param tokenSecret 私钥
     * @return
     */
    public static String parseToken(String token, String tokenSecret) {
        try {
            return Jwts.parser()
                    .setSigningKey(tokenSecret)
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();
        }catch (ExpiredJwtException jwtException) {
            log.error("parseToken error",jwtException);
            throw new DaMaiFrameException(BaseCode.TOKEN_EXPIRE);
        }
        
    }
    
    public static void main(String[] args) {
        
         String tokenSecret = "CSYZWECHAT";
        
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("001key", "001value");
        jsonObject.put("002key", "001value");

        String token1 = TokenUtil.createToken("1", jsonObject.toJSONString(), 10000, tokenSecret);
        System.out.println("token:" + token1);
        
        
        String token2 = "eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiIxIiwiaWF0IjoxNjg4NTQyODM3LCJzdWIiOiJ7XCIwMDJrZXlcIjpcIjAwMXZhbHVlXCIsXCIwMDFrZXlcIjpcIjAwMXZhbHVlXCJ9IiwiZXhwIjoxNjg4NTQyODQ3fQ.vIKcAilTn_CR3VYssNE7rBpfuCSCH_RrkmsadLWf664";
        String subject = TokenUtil.parseToken(token2, tokenSecret);
        System.out.println("解析token后的值:" + subject);
    }
}