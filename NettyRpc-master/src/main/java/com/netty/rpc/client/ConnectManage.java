package com.netty.rpc.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RPC Connect Manage of ZooKeeper
 * Created by luxiaoxun on 2016-03-16.
 */
public class ConnectManage {
    private static final Logger logger = LoggerFactory.getLogger(ConnectManage.class);
    private volatile static ConnectManage connectManage;

    private EventLoopGroup eventLoopGroup = new NioEventLoopGroup(4);
    private static ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(16, 16,
            600L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(65536));

    // 采用线程安全类型的List，ArrayList不是线程安全的，线程安全的List只有两类：synchronizedList和CopyOnWriteArrayList
    private CopyOnWriteArrayList<RpcClientHandler> connectedHandlers = new CopyOnWriteArrayList<>();

    // 存储一个RpcServer的ip:port地址以及连接到这个地址的连接中的RpcClientHandler
    // 在一个RpcClient中，有一个connectedServerNodes对象，来存储连接以及连接中的特定handler
    private Map<InetSocketAddress, RpcClientHandler> connectedServerNodes = new ConcurrentHashMap<>();

    private ReentrantLock lock = new ReentrantLock();
    private Condition connected = lock.newCondition();
    private long connectTimeoutMillis = 6000;
    private AtomicInteger roundRobin = new AtomicInteger(0);
    private volatile boolean isRuning = true;

    private ConnectManage() {
    }

    // 单例模式
    public static ConnectManage getInstance() {
        if (connectManage == null) {
            synchronized (ConnectManage.class) {
                if (connectManage == null) {
                    connectManage = new ConnectManage();
                }
            }
        }
        return connectManage;
    }

    /**
     * @param allServerAddress 表示现在 Zookeeper 集群上所有服务器的地址(形式为ip:port)
     */
    public void updateConnectedServer(List<String> allServerAddress) {
        if (allServerAddress != null) {
            // 获取依然最新的RpcServer地址
            if (allServerAddress.size() > 0) {
                //update local serverNodes cache
                HashSet<InetSocketAddress> newAllServerNodeSet = new HashSet<InetSocketAddress>();

                // 将 String 类型的 RpcServer 地址转化为 InetSocketAddress 类型的地址，并且保存到 newAllServerNodeSet 中
                for (int i = 0; i < allServerAddress.size(); ++i) {
                    String[] array = allServerAddress.get(i).split(":");
                    // Should check IP and port
                    if (array.length == 2) {
                        String host = array[0];
                        int port = Integer.parseInt(array[1]);
                        final InetSocketAddress remotePeer = new InetSocketAddress(host, port);
                        newAllServerNodeSet.add(remotePeer);
                    }
                }

                // Add new server node
                // 让客户端与新的 RpcServer 地址建立长连接，并且把连接中的 RpcClientHandler 添加到 connectedHandlers，然后把
                // 服务器的地址与 RpcClientHandler 一起添加到 connectedServerNodes 中
                for (final InetSocketAddress serverNodeAddress : newAllServerNodeSet) {
                    if (!connectedServerNodes.keySet().contains(serverNodeAddress)) {
                        connectServerNode(serverNodeAddress);
                    }
                }

                // Close and remove invalid server nodes
                // 由于可能有些服务器更换地址或者说掉线，所以从 connectedHandlers 中移除调对应连接中的 handler
                // 同样从 connectedServerNodes 也移除对应的 handler 和 requestId
                for (int i = 0; i < connectedHandlers.size(); ++i) {
                    RpcClientHandler connectedServerHandler = connectedHandlers.get(i);
                    SocketAddress remotePeer = connectedServerHandler.getRemotePeer();
                    if (!newAllServerNodeSet.contains(remotePeer)) {
                        logger.info("Remove invalid server node " + remotePeer);
                        RpcClientHandler handler = connectedServerNodes.get(remotePeer);
                        if (handler != null) {
                            handler.close();
                        }
                        connectedServerNodes.remove(remotePeer);
                        connectedHandlers.remove(connectedServerHandler);
                    }
                }
            } else { // No available server node ( All server nodes are down )
                // 如果从 Zookeeper 注册中心中没有发现服务器地址，那么关闭 connectedServerNodes 中所有 RpcHandler 的，
                // 并且清空 connectedHandlers
                logger.error("No available server node. All server nodes are down !!!");
                for (final RpcClientHandler connectedServerHandler : connectedHandlers) {
                    SocketAddress remotePeer = connectedServerHandler.getRemotePeer();
                    RpcClientHandler handler = connectedServerNodes.get(remotePeer);
                    handler.close();
                    connectedServerNodes.remove(connectedServerHandler);
                }
                connectedHandlers.clear();
            }
        }
    }

    public void reconnect(final RpcClientHandler handler, final SocketAddress remotePeer) {
        if (handler != null) {
            connectedHandlers.remove(handler);
            connectedServerNodes.remove(handler.getRemotePeer());
        }
        connectServerNode((InetSocketAddress) remotePeer);
    }

    private void connectServerNode(final InetSocketAddress remotePeer) {
        threadPoolExecutor.submit(() -> {
            Bootstrap b = new Bootstrap();
            b.group(eventLoopGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new RpcClientInitializer());

            ChannelFuture channelFuture = b.connect(remotePeer);
            channelFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture channelFuture) throws Exception {
                    if (channelFuture.isSuccess()) {
                        logger.debug("Successfully connect to remote server. remote peer = " + remotePeer);
                        RpcClientHandler handler = channelFuture.channel().pipeline().get(RpcClientHandler.class);
                        addHandler(handler);
                    }
                }
            });
        });
    }

    private void addHandler(RpcClientHandler handler) {
        connectedHandlers.add(handler);
        InetSocketAddress remoteAddress = (InetSocketAddress) handler.getChannel().remoteAddress();
        connectedServerNodes.put(remoteAddress, handler);
        // 唤醒所有在等待客户端与服务器端连接的线程
        signalAvailableHandler();
    }

    private void signalAvailableHandler() {
        lock.lock();
        try {
            connected.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private boolean waitingForHandler() throws InterruptedException {
        lock.lock();
        try {
            return connected.await(this.connectTimeoutMillis, TimeUnit.MILLISECONDS);
        } finally {
            lock.unlock();
        }
    }

    public RpcClientHandler chooseHandler() {
        int size = connectedHandlers.size();
        // size = 0 说明现在客户端还没有和任何服务器建立连接，因此调用 waitingForHandler 方法阻塞6s进行等待，
        // 当客户端一旦和某个服务器端建立连接之后（调用connectServerNode方法），就会通知所有的等待在 connected 上面的线程
        // 线程被唤醒返回或者超时返回之后，重新获取客户端与服务器端的连接数量，如果大于0，则跳出自旋
        while (isRuning && size <= 0) {
            try {
                waitingForHandler();
                size = connectedHandlers.size();
            } catch (InterruptedException e) {
                logger.error("Waiting for available node is interrupted! ", e);
                throw new RuntimeException("Can't connect any servers!", e);
            }
        }
        int index = (roundRobin.getAndAdd(1) + size) % size;
        return connectedHandlers.get(index);
    }

    public void stop() {
        isRuning = false;
        for (int i = 0; i < connectedHandlers.size(); ++i) {
            RpcClientHandler connectedServerHandler = connectedHandlers.get(i);
            connectedServerHandler.close();
        }
        signalAvailableHandler();
        threadPoolExecutor.shutdown();
        eventLoopGroup.shutdownGracefully();
    }
}
