package com.netty.rpc.client;

import com.netty.rpc.client.proxy.ObjectProxy;
import com.netty.rpc.registry.ServiceDiscovery;
import com.netty.rpc.client.proxy.IAsyncObjectProxy;

import java.lang.reflect.Proxy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * RPC Client（Create RPC proxy）
 *
 * 首先启动Zookeeper服务器，然后启动RpcServer，此RpcServer就是用来为RpcClient提供服务的。RpcServer的具体启动流程是首先
 * 启动Netty，监听此服务器的ip地址和端口：127.0.0.1:18866，然后连接到Zookeeper集群，并且把此RpcServer的地址和端口号写入到Zookeeper中，
 * 比如/registry/data01 127.0.0.1:18866。接下来启动客户端RpcClient，首先会从Zookeeper中去获取服务器的地址列表，并且对节点/registry设置监听，然后客户端
 * 会和所获取的服务器列表中的每个服务器建立长连接。
 */
public class RpcClient {

    private String serverAddress;

    private ServiceDiscovery serviceDiscovery;

    private static ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(16, 16,
            600L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(65536));

    public RpcClient(String serverAddress) {
        this.serverAddress = serverAddress;
    }

    public RpcClient(ServiceDiscovery serviceDiscovery) {
        this.serviceDiscovery = serviceDiscovery;
    }

    @SuppressWarnings("unchecked")
    public static <T> T create(Class<T> interfaceClass) {
        //创建一个代理对象，这个代理对象实现了 interfaceClass 接口，比如 HelloService
        return (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[]{interfaceClass},
                new ObjectProxy<T>(interfaceClass)
        );
    }

    public static <T> IAsyncObjectProxy createAsync(Class<T> interfaceClass) {
        return new ObjectProxy<T>(interfaceClass);
    }

    public static void submit(Runnable task) {
        threadPoolExecutor.submit(task);
    }

    public void stop() {
        threadPoolExecutor.shutdown();
        serviceDiscovery.stop();
        ConnectManage.getInstance().stop();
    }
}


