package com.damai.service.init;

import com.damai.BusinessThreadPool;
import com.damai.core.SpringUtil;
import com.damai.dto.EsDocumentMappingDto;
import com.damai.entity.TicketCategoryAggregate;
import com.damai.initialize.base.AbstractApplicationPostConstructHandler;
import com.damai.service.ProgramService;
import com.damai.util.BusinessEsHandle;
import com.damai.vo.ProgramVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @program: 极度真实还原大麦网高并发实战项目。 添加 阿星不是程序员 微信，添加时备注 大麦 来获取项目的完整资料 
 * @description: 节目数据ES索引初始化器,负责在应用启动时将数据库中的节目数据同步到ES中. 支持业务的场景:节目搜索功能,主页分类展示,定时重建机制,数据变更时的索引更新
 * @author: 阿星不是程序员
 **/
@Slf4j
@Component
public class ProgramElasticsearchInitData extends AbstractApplicationPostConstructHandler {
    
    @Autowired
    private BusinessEsHandle businessEsHandle;
    
    @Autowired
    private ProgramService programService;
    
    //初始化顺序控制,确保基础数据已准备好
    @Override
    public Integer executeOrder() {
        return 3;
    }
    //异步执行策略
    @Override
    public void executeInit(final ConfigurableApplicationContext context) {
        BusinessThreadPool.execute(() -> {//异步执行,不阻塞应用启动
            try {
                initElasticsearchData();
            }catch (Exception e) {
                log.error("executeInit error",e);
            }
        });
    }
    //创建索引
    public void initElasticsearchData(){
        if (!indexAdd()) {//如果索引存在,则不继续执行
            return;
        }
        //查询所有节目id
        List<Long> allProgramIdList = programService.getAllProgramIdList();
        //根据节目id统计出票档的最低价和最高价的集合map,key为节目id, value为票档
        Map<Long, TicketCategoryAggregate> ticketCategorieMap = programService.selectTicketCategorieMap(allProgramIdList);
        // 逐个节目转换并写入ES中
        for (Long programId : allProgramIdList) {
            //从数据库中查出详细的节目信息
            ProgramVo programVo = programService.getDetailFromDb(programId);
            //构建ES文档数据
            Map<String,Object> map = new HashMap<>(32);
            //基础节目信息
            map.put(ProgramDocumentParamName.ID,programVo.getId());
            map.put(ProgramDocumentParamName.PROGRAM_GROUP_ID,programVo.getProgramGroupId());
            map.put(ProgramDocumentParamName.PRIME,programVo.getPrime());
            map.put(ProgramDocumentParamName.TITLE,programVo.getTitle());

            map.put(ProgramDocumentParamName.ACTOR,programVo.getActor());
            map.put(ProgramDocumentParamName.PLACE,programVo.getPlace());
            map.put(ProgramDocumentParamName.ITEM_PICTURE,programVo.getItemPicture());
            map.put(ProgramDocumentParamName.AREA_ID,programVo.getAreaId());

            map.put(ProgramDocumentParamName.AREA_NAME,programVo.getAreaName());
            //分类和地区
            map.put(ProgramDocumentParamName.PROGRAM_CATEGORY_ID,programVo.getProgramCategoryId());
            map.put(ProgramDocumentParamName.PROGRAM_CATEGORY_NAME,programVo.getProgramCategoryName());
            map.put(ProgramDocumentParamName.PARENT_PROGRAM_CATEGORY_ID,programVo.getParentProgramCategoryId());
            map.put(ProgramDocumentParamName.PARENT_PROGRAM_CATEGORY_NAME,programVo.getParentProgramCategoryName());
            map.put(ProgramDocumentParamName.HIGH_HEAT,programVo.getHighHeat());
            map.put(ProgramDocumentParamName.ISSUE_TIME,programVo.getIssueTime());
            map.put(ProgramDocumentParamName.SHOW_TIME, programVo.getShowTime());
            map.put(ProgramDocumentParamName.SHOW_DAY_TIME,programVo.getShowDayTime());
            map.put(ProgramDocumentParamName.SHOW_WEEK_TIME,programVo.getShowWeekTime());
            //价格信息(从票务聚合获取数据)
            map.put(ProgramDocumentParamName.MIN_PRICE,
                    Optional.ofNullable(ticketCategorieMap.get(programVo.getId()))
                            .map(TicketCategoryAggregate::getMinPrice).orElse(null));
            map.put(ProgramDocumentParamName.MAX_PRICE,
                    Optional.ofNullable(ticketCategorieMap.get(programVo.getId()))
                            .map(TicketCategoryAggregate::getMaxPrice).orElse(null));
            businessEsHandle.add(SpringUtil.getPrefixDistinctionName() + "-" + 
                    ProgramDocumentParamName.INDEX_NAME, ProgramDocumentParamName.INDEX_TYPE,map);
        }
    }
    
    public boolean indexAdd(){
        //检查索引是否存在
        boolean result = businessEsHandle.checkIndex(SpringUtil.getPrefixDistinctionName() + "-" +
                ProgramDocumentParamName.INDEX_NAME, ProgramDocumentParamName.INDEX_TYPE);
        if (result) {
            //存在则删除旧索引
            businessEsHandle.deleteIndex(SpringUtil.getPrefixDistinctionName() + "-" +
                    ProgramDocumentParamName.INDEX_NAME);
        }
        try {
            //创建新索引和映射
            businessEsHandle.createIndex(SpringUtil.getPrefixDistinctionName() + "-" +
                    ProgramDocumentParamName.INDEX_NAME, ProgramDocumentParamName.INDEX_TYPE,getEsMapping());
            return true;
        }catch (Exception e) {
            log.error("createIndex error",e);
        }
        return false;
    }
    /*
    * 组装索引的mapping结构, 相当于数据库的表结构. 字符串映射定义
    * */
    public List<EsDocumentMappingDto> getEsMapping(){
        List<EsDocumentMappingDto> list = new ArrayList<>();
        //基础信息字段
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.ID,"long"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.PROGRAM_GROUP_ID,"integer"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.PRIME,"long"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.TITLE,"text"));//支持全文搜索
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.ACTOR,"text"));//支持演员搜索
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.PLACE,"text"));//支持场地搜索
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.ITEM_PICTURE,"text"));
        //分类和地区字段
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.AREA_ID,"long"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.AREA_NAME,"text"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.PROGRAM_CATEGORY_ID,"long"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.PROGRAM_CATEGORY_NAME,"text"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.PARENT_PROGRAM_CATEGORY_ID,"long"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.PARENT_PROGRAM_CATEGORY_NAME,"text"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.HIGH_HEAT,"integer"));
        //时间字段
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.ISSUE_TIME,"date"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.SHOW_TIME,"date"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.SHOW_DAY_TIME,"date"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.SHOW_WEEK_TIME,"text"));
        //价格字段
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.MIN_PRICE,"integer"));
        list.add(new EsDocumentMappingDto(ProgramDocumentParamName.MAX_PRICE,"integer"));
        
        return list;
    }
}
