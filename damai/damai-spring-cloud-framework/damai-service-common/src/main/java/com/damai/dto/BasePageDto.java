package com.damai.dto;


import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @program: 极度真实还原大麦网高并发实战项目。 添加 阿星不是程序员 微信，添加时备注 大麦 来获取项目的完整资料 
 * @description: 在进行分页查询时，入参的实体中需要传入pageNumber，pageSize这两个参数，
 * 每个分页查询接口的入参实体都要有这两个参数，接口多了就造成了冗余，同样将这两个参数抽取成一个公共实体BasePageDto，再有分页查询时，参数实体只要继承即可
 * @author: 阿星不是程序员
 **/
@Data
public class BasePageDto {
    
    
    @NotNull
    private Integer pageNumber;
    
    
    @NotNull
    private Integer pageSize;
}
