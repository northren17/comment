[@EnableAspectJAutoProxy(exposeProxy=true)

作用：
暴露当前 Spring AOP 代理对象

AopContext.currentProxy():
获取当前正在执行的代理对象

为什么需要？
this调用不会经过代理 → 事务失效

解决方案：
使用 proxy 调用方法，保证事务生效](com.hmdp.service.impl.VoucherOrderServiceImpl.seckillVoucher)



@EnableAspectJAutoProxy(exposeProxy=true) 是干嘛的？
👉 默认情况：

Spring 不允许你拿到当前代理对象

👉 加了这个：
@EnableAspectJAutoProxy(exposeProxy = true)

👉 就等于开启：

允许你在代码中通过 AopContext 拿到当前代理对象

🧩 五、AopContext.currentProxy() 是什么？
IVoucherOrderService proxy =
(IVoucherOrderService) AopContext.currentProxy();

👉 作用：

拿到当前正在执行的 Spring 代理对象

🔥 六、你这段代码真正发生了什么
synchronized (userid.toString().intern()) {

    IVoucherOrderService proxy =
        (IVoucherOrderService) AopContext.currentProxy();

    return proxy.createVoucherOrder(voucherId);
}
👉 执行流程：
1. 进入方法
2. synchronized 锁住用户
3. 获取 Spring 代理对象（proxy）
4. 通过 proxy 调用方法
5. 事务生效 ✔
   ⚠️ 七、为什么一定要 proxy？

如果你写：

this.createVoucherOrder()

👉 结果：

事务不生效 ❌

如果你写：

proxy.createVoucherOrder()

👉 结果：

事务生效 ✔