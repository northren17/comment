package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.Duration;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.RedisConstants.STREAM_ORDERS_KEY;


@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private TransactionTemplate transactionTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String STREAM_GROUP = "g1";
    private static final String STREAM_CONSUMER = "c1";

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    private void init() {
        initStreamGroup();
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private void initStreamGroup() {
        try {
            stringRedisTemplate.opsForStream().createGroup(STREAM_ORDERS_KEY, ReadOffset.latest(), STREAM_GROUP);
        } catch (Exception e) {
            String message = e.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return;
            }
            try {
                StringRecord initRecord = StringRecord.of(STREAM_ORDERS_KEY, Collections.singletonMap("init", "0"));
                stringRedisTemplate.opsForStream().add(initRecord);
                stringRedisTemplate.opsForStream().createGroup(STREAM_ORDERS_KEY, ReadOffset.from("0-0"), STREAM_GROUP);
            } catch (Exception ex) {
                String busyGroupMessage = ex.getMessage();
                if (busyGroupMessage == null || !busyGroupMessage.contains("BUSYGROUP")) {
                    log.error("初始化 Stream 消费组失败", ex);
                }
            }
        }
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(STREAM_GROUP, STREAM_CONSUMER),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_ORDERS_KEY, ReadOffset.lastConsumed())
                    );
                    if (records == null || records.isEmpty()) {
                        continue;
                    }
                    MapRecord<String, Object, Object> record = records.get(0);
                    VoucherOrder voucherOrder = buildVoucherOrder(record.getValue());
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDERS_KEY, STREAM_GROUP, record.getId());
                } catch (Exception e) {
                    log.error("处理订单消息异常", e);
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        while (true) {
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(STREAM_GROUP, STREAM_CONSUMER),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(STREAM_ORDERS_KEY, ReadOffset.from("0"))
                );
                if (records == null || records.isEmpty()) {
                    break;
                }
                MapRecord<String, Object, Object> record = records.get(0);
                VoucherOrder voucherOrder = buildVoucherOrder(record.getValue());
                handleVoucherOrder(voucherOrder);
                stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDERS_KEY, STREAM_GROUP, record.getId());
            } catch (Exception e) {
                log.error("处理 pending-list 订单异常", e);
                break;
            }
        }
    }

    private VoucherOrder buildVoucherOrder(Map<Object, Object> values) {
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(Long.valueOf(values.get("orderId").toString()));
        voucherOrder.setUserId(Long.valueOf(values.get("userId").toString()));
        voucherOrder.setVoucherId(Long.valueOf(values.get("voucherId").toString()));
        return voucherOrder;
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        transactionTemplate.execute(status -> {
            Long userId = voucherOrder.getUserId();
            Long voucherId = voucherOrder.getVoucherId();
            long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                return null;
            }
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0)
                    .update();
            if (!success) {
                return null;
            }
            save(voucherOrder);
            return null;
        });
    }

    /*
    秒杀抢券
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long executeResult = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(SECKILL_STOCK_KEY + voucherId, SECKILL_ORDER_KEY + voucherId),
                userId.toString(),
                voucherId.toString(),
                String.valueOf(orderId)
        );
        if (executeResult == null) {
            return Result.fail("抢购失败，请重试");
        }
        int result = executeResult.intValue();
        if (result == 1) {
            return Result.fail("库存不足");
        }
        if (result == 2) {
            return Result.fail("不能重复下单");
        }
        if (result == 3) {
            return Result.fail("秒杀券不存在");
        }
        return Result.ok(orderId);
    }

    @Override
    public Result createVoucherOrder(Long voucherId) {
        // 订单落库流程后续由调用方完善
        return Result.fail("下单流程待完善");
    }
}

