package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    // flag to stop background handler when a fatal Redis error occurs
    private volatile boolean stopHandler = false;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private final ExecutorService seckillOrderExecutor = Executors.newSingleThreadExecutor();
    private static final String STREAM_GROUP = "g1";
    private static final String STREAM_CONSUMER = "c1";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 2000;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    private void init() {
        initStreamGroup();
        seckillOrderExecutor.submit(new VoucherOrderHandler());
    }

    @PreDestroy
    private void destroy() {
        log.info("Shutting down VoucherOrderService executor...");
        // Attempt to interrupt running tasks and stop the executor
        seckillOrderExecutor.shutdownNow();
        try {
            if (!seckillOrderExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("seckillOrderExecutor did not terminate within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void initStreamGroup() {
        try {
            stringRedisTemplate.opsForStream().createGroup(STREAM_ORDERS_KEY, ReadOffset.latest(), STREAM_GROUP);
        } catch (Exception e) {
            String message = e.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                log.debug("Stream 消费组已存在: {}", STREAM_GROUP);
                return;
            }
            try {
                stringRedisTemplate.opsForStream().add(STREAM_ORDERS_KEY, Collections.singletonMap("init", "0"));
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
            int retryCount = 0;
            while (!stopHandler && !Thread.currentThread().isInterrupted()) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(STREAM_GROUP, STREAM_CONSUMER),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_ORDERS_KEY, ReadOffset.lastConsumed())
                    );
                    if (records == null || records.isEmpty()) {
                        retryCount = 0; // 重置重试计数器
                        // Check if thread was interrupted and exit
                        if (Thread.currentThread().isInterrupted()) {
                            log.info("VoucherOrderHandler thread interrupted, exiting");
                            break;
                        }
                        continue;
                    }

                    MapRecord<String, Object, Object> record = records.get(0);
                    VoucherOrder voucherOrder = buildVoucherOrder(record.getValue());
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDERS_KEY, STREAM_GROUP, record.getId());
                    retryCount = 0; // 成功处理后重置重试计数器
                } catch (Exception e) {
                    // Detect Redis server that doesn't support streams (unknown XREADGROUP)
                    Throwable cause = e;
                    while (cause != null) {
                        String msg = cause.getMessage();
                        if (msg != null && msg.contains("unknown command 'XREADGROUP'")) {
                            log.error("Redis server does not support Streams (XREADGROUP). Stopping VoucherOrderHandler to avoid busy loop.", e);
                            stopHandler = true; // request stop
                            break;
                        }
                        cause = cause.getCause();
                    }
                    if (stopHandler) {
                        break;
                    }
                    // If Redis connection factory was destroyed during shutdown, stop the handler loop
                    if (e instanceof IllegalStateException && e.getMessage() != null && e.getMessage().contains("LettuceConnectionFactory was destroyed")) {
                        log.warn("Redis connection factory destroyed, stopping VoucherOrderHandler thread");
                        break;
                    }

                    retryCount++;
                    log.error("处理订单消息异常 (重试次数: {}/{})", retryCount, MAX_RETRY_ATTEMPTS, e);

                    if (retryCount >= MAX_RETRY_ATTEMPTS) {
                        log.warn("达到最大重试次数，等待 {} 毫秒后重试", RETRY_DELAY_MS);
                        try {
                            Thread.sleep(RETRY_DELAY_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        retryCount = 0; // 重置重试计数器
                    } else {
                        try {
                            Thread.sleep(1000); // 短暂休眠
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }
    }

    private void handlePendingList() {
        int retryCount = 0;
        while (retryCount < MAX_RETRY_ATTEMPTS) {
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(STREAM_GROUP, STREAM_CONSUMER),
                        StreamReadOptions.empty().count(10), // 一次处理多条消息，提高效率
                        StreamOffset.create(STREAM_ORDERS_KEY, ReadOffset.from("0"))
                );

                if (records == null || records.isEmpty()) {
                    return;
                }

                for (MapRecord<String, Object, Object> record : records) {
                    try {
                        VoucherOrder voucherOrder = buildVoucherOrder(record.getValue());
                        handleVoucherOrder(voucherOrder);
                        stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDERS_KEY, STREAM_GROUP, record.getId());
                    } catch (Exception e) {
                        log.error("处理单个pending订单异常", e);
                        // 不要在这里调用自身，避免递归
                    }
                }
                return; // 成功处理后直接返回
            } catch (Exception e) {
                // Detect unsupported XREADGROUP in pending-list processing too
                Throwable cause = e;
                boolean unsupported = false;
                while (cause != null) {
                    String msg = cause.getMessage();
                    if (msg != null && msg.contains("unknown command 'XREADGROUP'")) {
                        log.error("Redis server does not support Streams (XREADGROUP). Aborting pending-list processing.", e);
                        unsupported = true;
                        break;
                    }
                    cause = cause.getCause();
                }
                if (unsupported) {
                    return;
                }
                retryCount++;
                log.error("批量处理 pending-list 订单异常 (重试次数: {}/{})", retryCount, MAX_RETRY_ATTEMPTS, e);
                if (retryCount >= MAX_RETRY_ATTEMPTS) {
                    log.warn("处理 pending-list 达到最大重试次数，放弃本次处理");
                    return;
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
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
        return Result.fail("下单流程待完善");
    }
}

