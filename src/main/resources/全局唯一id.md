1️⃣ 日期格式

👉 yyyy:MM:dd 只是为了：
String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
ofPattern(...)：定义“格式规则”
format(...)：按这个规则把时间转成字符串
可读性
层级清晰
👉 yyyyMMdd 也可以
2️⃣ long vs Long
// 2.2.自增长
long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

👉 用 long 是为了：

避免空指针
提升性能
Redis保证返回值不为null
3️⃣ keyPrefix 空指针

👉 不会报错（字符串拼接特性）
👉 但会产生错误key，应该手动校验

# **超卖问题**

1. **_乐观锁定义_**
   认为冲突概率低，不加锁，在更新时进行条件判断

2. 核心思想
   更新时校验数据是否被修改

3. 实现方式
- 版本号机制（version）
- CAS机制（如 stock > 0）

4. 优点
- 无锁，性能高
- 并发能力强

5. 缺点
- 高并发下失败率高
- 可能需要重试

6. 应用场景
- 秒杀系统
- 库存扣减 
7.问题
 1.  CAS在高并发下自旋严重，性能下降

2. 解决方案
   LongAdder（分段CAS）

3. 原理
   将一个变量拆成多个cell，线程分散更新