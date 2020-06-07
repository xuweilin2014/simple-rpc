package com.netty.rpc.client;

import com.netty.rpc.protocol.RpcRequest;
import com.netty.rpc.protocol.RpcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.ReentrantLock;

public class RPCFuture implements Future<Object> {

    private static final Logger logger = LoggerFactory.getLogger(RPCFuture.class);

    private Sync sync;

    private RpcRequest request;

    private RpcResponse response;

    private long startTime;

    //如果从调用到服务端返回响应的时间间隔大于 responseTimeThreshold，则记录到日志中
    private long responseTimeThreshold = 5000;

    private List<AsyncRPCCallback> pendingCallbacks = new ArrayList<AsyncRPCCallback>();

    private ReentrantLock lock = new ReentrantLock();

    public RPCFuture(RpcRequest request) {
        this.sync = new Sync();
        this.request = request;
        this.startTime = System.currentTimeMillis();
    }

    @Override
    public boolean isDone() {
        return sync.isDone();
    }

    //获取Rpc调用的结果，如果结果还没从RpcServer返回，那么直接阻塞，直到结果返回
    @Override
    public Object get() {
        sync.acquire(-1);
        if (this.response != null) {
            return this.response.getResult();
        } else {
            return null;
        }
    }

    //获取Rpc调用的结果，如果结果还没从RpcServer返回，那么直接阻塞 timeout 个时间单位，如果在 timeout 个时间单位里面
    //还没有返回，那么就会抛出异常。
    @Override
    public Object get(long timeout, TimeUnit unit) throws InterruptedException {
        boolean success = sync.tryAcquireNanos(-1, unit.toNanos(timeout));
        if (success) {
            if (this.response != null) {
                return this.response.getResult();
            } else {
                return null;
            }
        } else {
            throw new RuntimeException("Timeout exception. Request id: " + this.request.getRequestId()
                    + ". Request class name: " + this.request.getClassName()
                    + ". Request method: " + this.request.getMethodName());
        }
    }

    @Override
    public boolean isCancelled() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        throw new UnsupportedOperationException();
    }

    public void done(RpcResponse reponse) {
        this.response = reponse;
        //唤醒正在阻塞等待RpcResponse的线程
        sync.release(1);
        invokeCallbacks();
        //Threshold
        long responseTime = System.currentTimeMillis() - startTime;
        if (responseTime > this.responseTimeThreshold) {
            logger.warn("Service response time is too slow. Request id = " + reponse.getRequestId() + ". Response Time = " + responseTime + "ms");
        }
    }

    private void invokeCallbacks() {
        lock.lock();
        try {
            for (final AsyncRPCCallback callback : pendingCallbacks) {
                runCallback(callback);
            }
        } finally {
            lock.unlock();
        }
    }

    public RPCFuture addCallback(AsyncRPCCallback callback) {
        lock.lock();
        try {
            //如果在调用 addCallback 添加回调对象的时候，RPC调用的响应已经从服务端返回，
            //则直接运行 callback
            if (isDone()) {
                runCallback(callback);
            } else {
                this.pendingCallbacks.add(callback);
            }
        } finally {
            lock.unlock();
        }
        return this;
    }

    private void runCallback(final AsyncRPCCallback callback) {
        final RpcResponse res = this.response;
        RpcClient.submit(new Runnable() {
            @Override
            public void run() {
                if (!res.isError()) {
                    callback.success(res.getResult());
                } else {
                    callback.fail(new RuntimeException("Response error", new Throwable(res.getError())));
                }
            }
        });
    }

    //独占锁机制，用来判断该request是否完成
    static class Sync extends AbstractQueuedSynchronizer {

        private static final long serialVersionUID = 1L;

        //future status
        private final int done = 1;
        private final int pending = 0;

        @Override
        protected boolean tryAcquire(int arg) {
            return getState() == done;
        }

        @Override
        protected boolean tryRelease(int arg) {
            if (getState() == pending) {
                //CAS设置 volatile int state = 1，CAS线程保证操作的原子性
                if (compareAndSetState(pending, done)) {
                    //唤醒已经执行rpcFuture.get()的所有线程
                    return true;
                } else {
                    return false;
                }
            } else {
                return true;
            }
        }

        protected boolean isDone() {
            return getState() == done;
        }
    }
}
