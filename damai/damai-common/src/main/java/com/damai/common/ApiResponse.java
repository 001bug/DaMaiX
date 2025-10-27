package com.damai.common;

import com.damai.enums.BaseCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @program: 极度真实还原大麦网高并发实战项目。 添加 阿星不是程序员 微信，添加时备注 大麦 来获取项目的完整资料 
 * @description: 接口返回体基础类
 * @author: ohmygoda
 **/
@Data
@Schema(title="ApiResponse", description ="数据响应规范结构")
public class ApiResponse<T> implements Serializable {
    
    @Schema(name ="code", type ="Integer", description ="响应码 0:成功 其余:失败")
    //状态码,通过BaseCode来配置,只有code=0表示成功,其它表示失败
    private Integer code;

    //成功或者失败的原因
    @Schema(name ="message", type ="String", description ="错误信息")
    private String message;

    //返回的具体数据
    @Schema(name ="data", description ="响应的具体数据")
    private T data;
    
    private ApiResponse() {}
    
    public static <T> ApiResponse<T> error(Integer code, String message) {
        ApiResponse<T> apiResponse = new ApiResponse<T>();
        apiResponse.code = code;
        apiResponse.message = message;
        return apiResponse;
    }

    public static <T> ApiResponse<T> error(String message) {
        ApiResponse<T> apiResponse = new ApiResponse<T>();
        apiResponse.code = -100;
        apiResponse.message = message;
        return apiResponse;
    }
    
    public static <T> ApiResponse<T> error(Integer code, T data) {
        ApiResponse<T> apiResponse = new ApiResponse<T>();
        apiResponse.code = -100;
        apiResponse.data = data;
        return apiResponse;
    }
    
    public static <T> ApiResponse<T> error(BaseCode baseCode) {
        ApiResponse<T> apiResponse = new ApiResponse<T>();
        apiResponse.code = baseCode.getCode();
        apiResponse.message = baseCode.getMsg();
        return apiResponse;
    }
    
    public static <T> ApiResponse<T> error(BaseCode baseCode, T data) {
        ApiResponse<T> apiResponse = new ApiResponse<T>();
        apiResponse.code = baseCode.getCode();
        apiResponse.message = baseCode.getMsg();
        apiResponse.data = data;
        return apiResponse;
    }

    public static <T> ApiResponse<T> error() {
        ApiResponse<T> apiResponse = new ApiResponse<T>();
        apiResponse.code = -100;
        apiResponse.message = "系统错误，请稍后重试!";
        return apiResponse;
    }
    
    public static <T> ApiResponse<T> ok() {
        ApiResponse<T> apiResponse = new ApiResponse<T>();
        apiResponse.code = 0;
        return apiResponse;
    }
    
    public static <T> ApiResponse<T> ok(T t) {
        ApiResponse<T> apiResponse = new ApiResponse<T>();
        apiResponse.code = 0;
        apiResponse.setData(t);
        return apiResponse;
    }
}
