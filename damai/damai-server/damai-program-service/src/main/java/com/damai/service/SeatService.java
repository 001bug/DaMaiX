package com.damai.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.damai.core.RedisKeyManage;
import com.damai.dto.ProgramGetDto;
import com.damai.dto.SeatAddDto;
import com.damai.dto.SeatBatchAddDto;
import com.damai.dto.SeatBatchRelateInfoAddDto;
import com.damai.dto.SeatListDto;
import com.damai.entity.ProgramShowTime;
import com.damai.entity.Seat;
import com.damai.enums.BaseCode;
import com.damai.enums.BusinessStatus;
import com.damai.enums.SeatType;
import com.damai.enums.SellStatus;
import com.damai.exception.DaMaiFrameException;
import com.damai.mapper.SeatMapper;
import com.damai.redis.RedisCache;
import com.damai.redis.RedisKeyBuild;
import com.damai.service.lua.ProgramSeatCacheData;
import com.damai.servicelock.LockType;
import com.damai.servicelock.annotion.ServiceLock;
import com.damai.util.DateUtils;
import com.damai.util.ServiceLockTool;
import com.damai.vo.ProgramVo;
import com.damai.vo.SeatRelateInfoVo;
import com.damai.vo.SeatVo;
import com.damai.vo.TicketCategoryVo;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.damai.core.DistributedLockConstants.GET_SEAT_LOCK;
import static com.damai.core.DistributedLockConstants.SEAT_LOCK;

/**
 * @program: 极度真实还原大麦网高并发实战项目。 添加 阿星不是程序员 微信，添加时备注 大麦 来获取项目的完整资料 
 * @description: 座位 service
 * @author: 阿星不是程序员
 **/
@Service
public class SeatService extends ServiceImpl<SeatMapper, Seat> {
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private SeatMapper seatMapper;
    
    @Autowired
    private ProgramService programService;
    
    @Autowired
    private ProgramShowTimeService programShowTimeService;
    
    @Autowired
    private ServiceLockTool serviceLockTool;
    
    @Autowired
    private TicketCategoryService ticketCategoryService;
    
    @Autowired
    private ProgramSeatCacheData programSeatCacheData;
    
    /**
     * 添加座位
     * */
    public Long add(SeatAddDto seatAddDto) {
        LambdaQueryWrapper<Seat> seatLambdaQueryWrapper = Wrappers.lambdaQuery(Seat.class)
                .eq(Seat::getProgramId, seatAddDto.getProgramId())
                .eq(Seat::getRowCode, seatAddDto.getRowCode())
                .eq(Seat::getColCode, seatAddDto.getColCode());
        Seat seat = seatMapper.selectOne(seatLambdaQueryWrapper);
        if (Objects.nonNull(seat)) {
            throw new DaMaiFrameException(BaseCode.SEAT_IS_EXIST);
        }
        seat = new Seat();
        BeanUtil.copyProperties(seatAddDto,seat);
        seat.setId(uidGenerator.getUid());
        seatMapper.insert(seat);
        return seat.getId();
    }
    
    @ServiceLock(lockType= LockType.Read,name = SEAT_LOCK,keys = {"#programId","#ticketCategoryId"})
    public List<SeatVo> selectSeatResolution(Long programId,Long ticketCategoryId,Long expireTime,TimeUnit timeUnit) {
        //从缓存中查询所有状态的座位
        List<SeatVo> seatVoList = getSeatVoListByCacheResolution(programId,ticketCategoryId);
        if (CollectionUtil.isNotEmpty(seatVoList)) {
            return seatVoList;
        }
        //如果redis中三种状态的座位都没有,说明根本没有往redis存放过
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, GET_SEAT_LOCK, new String[]{String.valueOf(programId),
                String.valueOf(ticketCategoryId)});
        lock.lock();
        try {
            //再从缓存中查询座位数据
            seatVoList = getSeatVoListByCacheResolution(programId,ticketCategoryId);
            if (CollectionUtil.isNotEmpty(seatVoList)) {
                return seatVoList;
            }
            //从数据库中查找. 构建一个查询条件
            LambdaQueryWrapper<Seat> seatLambdaQueryWrapper =
                    Wrappers.lambdaQuery(Seat.class).eq(Seat::getProgramId, programId)//Seat::getProgramId是方法引用,并且是链式调用
                            .eq(Seat::getTicketCategoryId,ticketCategoryId);
            List<Seat> seats = seatMapper.selectList(seatLambdaQueryWrapper);
            for (Seat seat : seats) {
                SeatVo seatVo = new SeatVo();
                BeanUtil.copyProperties(seat, seatVo);
                seatVo.setSeatTypeName(SeatType.getMsg(seat.getSeatType()));
                seatVoList.add(seatVo);
            }
            //将作为按照状态进行分类. Collectors收集器
            Map<Integer, List<SeatVo>> seatMap = seatVoList.stream().collect(Collectors.groupingBy(SeatVo::getSellStatus));
            //没有售卖的座位
            List<SeatVo> noSoldSeatVoList = seatMap.get(SellStatus.NO_SOLD.getCode());
            //正在锁定的座位
            List<SeatVo> lockSeatVoList = seatMap.get(SellStatus.LOCK.getCode());
            //已经售卖的座位
            List<SeatVo> soldSeatVoList = seatMap.get(SellStatus.SOLD.getCode());
            if (CollectionUtil.isNotEmpty(noSoldSeatVoList)) {
                //将没有售卖的座位放入redis中,以字符串id为key,数据本身为value,遇到重复的覆盖
                redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, 
                                programId,ticketCategoryId),noSoldSeatVoList.stream()
                                .collect(Collectors.toMap(s -> String.valueOf(s.getId()),s -> s,(v1,v2) -> v2))
                        ,expireTime, timeUnit);
            }
            if (CollectionUtil.isNotEmpty(lockSeatVoList)) {
                redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, 
                                programId,ticketCategoryId),lockSeatVoList.stream()
                                .collect(Collectors.toMap(s -> String.valueOf(s.getId()),s -> s,(v1,v2) -> v2))
                        ,expireTime, timeUnit);
            }
            if (CollectionUtil.isNotEmpty(soldSeatVoList)) {
                redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH, 
                                programId,ticketCategoryId)
                        ,soldSeatVoList.stream()
                                .collect(Collectors.toMap(s -> String.valueOf(s.getId()),s -> s,(v1,v2) -> v2))
                        ,expireTime, timeUnit);
            }
            seatVoList = seatVoList.stream().sorted(Comparator.comparingInt(SeatVo::getRowCode)
                    .thenComparingInt(SeatVo::getColCode)).collect(Collectors.toList());
            return seatVoList;
        }finally {
            lock.unlock();
        }
    }
    
    public List<SeatVo> getSeatVoListByCacheResolution(Long programId,Long ticketCategoryId){
        List<String> keys = new ArrayList<>(4);
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH,
                programId, ticketCategoryId).getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH,
                programId, ticketCategoryId).getRelKey());
        keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH,
                programId, ticketCategoryId).getRelKey());
        return programSeatCacheData.getData(keys, new String[]{});
    }
    //从redis中查找节目座位信息
    public SeatRelateInfoVo relateInfo(SeatListDto seatListDto) {
        SeatRelateInfoVo seatRelateInfoVo = new SeatRelateInfoVo();
        //从redis中查询节目
        ProgramVo programVo = 
                redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM,seatListDto.getProgramId()),ProgramVo.class);
        //查询成功,直接返回
        if (Objects.isNull(programVo)){
            ProgramGetDto programGetDto = new ProgramGetDto();
            programGetDto.setId(seatListDto.getProgramId());
            programVo = programService.detail(programGetDto);
        }
        //查询演出时间
        ProgramShowTime programShowTime = programShowTimeService.selectProgramShowTimeByProgramId(seatListDto.getProgramId());
        List<TicketCategoryVo> ticketCategoryVoList = ticketCategoryService
                .selectTicketCategoryListByProgramIdMultipleCache(programVo.getId(),programShowTime.getShowTime());
        
        List<SeatVo> seatVos = new ArrayList<>();
        //遍历票档集合
        for (TicketCategoryVo ticketCategoryVo : ticketCategoryVoList) {
            //根据节目id和票档id来查询出座位集合,汇总到seatVos中
            seatVos.addAll(selectSeatResolution(seatListDto.getProgramId(),ticketCategoryVo.getId(),
                    DateUtils.countBetweenSecond(DateUtils.now(), programShowTime.getShowTime()), TimeUnit.SECONDS));
        }
        //如果节目不允许选座位,直接抛出异常
        if (programVo.getPermitChooseSeat().equals(BusinessStatus.NO.getCode())) {
            throw new DaMaiFrameException(BaseCode.PROGRAM_NOT_ALLOW_CHOOSE_SEAT);
        }
        
        Map<String, List<SeatVo>> seatVoMap =
                seatVos.stream().collect(Collectors.groupingBy(seatVo -> seatVo.getPrice().toString()));
        //节目id
        seatRelateInfoVo.setProgramId(programVo.getId());
        //节目地点
        seatRelateInfoVo.setPlace(programVo.getPlace());
        //节目演出时间
        seatRelateInfoVo.setShowTime(programShowTime.getShowTime());
        seatRelateInfoVo.setShowWeekTime(programShowTime.getShowWeekTime());
        //价格列表
        seatRelateInfoVo.setPriceList(seatVoMap.keySet().stream().sorted().collect(Collectors.toList()));
        //节目的座位列表
        seatRelateInfoVo.setSeatVoMap(seatVoMap);
        return seatRelateInfoVo;
    }
    
    @Transactional(rollbackFor = Exception.class)
    public Boolean batchAdd(SeatBatchAddDto seatBatchAddDto) {
        Long programId = seatBatchAddDto.getProgramId();
        List<SeatBatchRelateInfoAddDto> seatBatchRelateInfoAddDtoList = seatBatchAddDto.getSeatBatchRelateInfoAddDtoList();
        
        
        int rowIndex = 0;
        for (SeatBatchRelateInfoAddDto seatBatchRelateInfoAddDto : seatBatchRelateInfoAddDtoList) {
            Long ticketCategoryId = seatBatchRelateInfoAddDto.getTicketCategoryId();
            BigDecimal price = seatBatchRelateInfoAddDto.getPrice();
            Integer count = seatBatchRelateInfoAddDto.getCount();
            
            int colCount = 10;
            int rowCount = count / colCount;
            
            for (int i = 1;i<= rowCount;i++) {
                rowIndex++;
                for (int j = 1;j<=colCount;j++) {
                    Seat seat = new Seat();
                    seat.setProgramId(programId);
                    seat.setTicketCategoryId(ticketCategoryId);
                    seat.setRowCode(rowIndex);
                    seat.setColCode(j);
                    seat.setSeatType(1);
                    seat.setPrice(price);
                    seat.setSellStatus(SellStatus.NO_SOLD.getCode());
                    seatMapper.insert(seat);
                }
            }
        }
        
        return true;
    }
}
