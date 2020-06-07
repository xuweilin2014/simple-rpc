package com.netty.rpc.registry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

import com.netty.rpc.client.ConnectManage;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在ServiceDiscovery这个bean初始化的时候，会建立和Zookeeper集群的连接，并且在Zookeeper的根结点/registry下面，
 * 获取到最新的服务器地址，然后和这些服务器地址建立长连接。并且会在/registry根结点上注册监听，一旦根结点下面的子节点有变化，就会
 * 重新扫描根节点下面的所有子节点，如果有新的子节点，就与新的服务器地址建立连接，如果有子节点从Zookeeper集群上被删除了，那么就
 * 删除和对应服务器的连接
 */
public class ServiceDiscovery {

    private static final Logger logger = LoggerFactory.getLogger(ServiceDiscovery.class);

    private CountDownLatch latch = new CountDownLatch(1);

    private volatile List<String> dataList = new ArrayList<>();

    //Zookeeper集群的ip地址和端口号
    private String registryAddress;

    private ZooKeeper zookeeper;

    public ServiceDiscovery(String registryAddress) {
        this.registryAddress = registryAddress;
        zookeeper = connectServer();
        if (zookeeper != null) {
            watchNode(zookeeper);
        }
    }

    public String discover() {
        String data = null;
        int size = dataList.size();
        if (size > 0) {
            if (size == 1) {
                data = dataList.get(0);
                logger.debug("using only data: {}", data);
            } else {
                data = dataList.get(ThreadLocalRandom.current().nextInt(size));
                logger.debug("using random data: {}", data);
            }
        }
        return data;
    }

    private ZooKeeper connectServer() {
        ZooKeeper zk = null;
        try {
            //通过地址 registryAddress（127.0.0.1:2181)连接到 Zookeeper 集群，超时时间为 5 秒
            zk = new ZooKeeper(registryAddress, Constant.ZK_SESSION_TIMEOUT, new Watcher() {
                @Override
                public void process(WatchedEvent event) {
                    if (event.getState() == Event.KeeperState.SyncConnected) {
                        latch.countDown();
                    }
                }
            });
            latch.await();
        } catch (IOException | InterruptedException e) {
            logger.error("", e);
        }
        return zk;
    }

    private void watchNode(final ZooKeeper zk) {
        try {
            //返回/registry下的所有子节点，并且注册监听，一旦/registry下的子节点数量发生变化，就会
            //回调watcher中的process方法
            List<String> nodeList = zk.getChildren(Constant.ZK_REGISTRY_PATH, new Watcher() {
                @Override
                public void process(WatchedEvent event) {
                    if (event.getType() == Event.EventType.NodeChildrenChanged) {
                        watchNode(zk);
                    }
                }
            });
            List<String> dataList = new ArrayList<>();
            for (String node : nodeList) {
                byte[] bytes = zk.getData(Constant.ZK_REGISTRY_PATH + "/" + node, false, null);
                dataList.add(new String(bytes));
            }
            logger.debug("node data: {}", dataList);

            //dataList为最新的注册在Zookeeper集群中的服务器地址
            this.dataList = dataList;
            logger.debug("Service discovery triggered updating connected server node.");

            //让客户端和现在 Zookeeper 集群中注册的最新的服务器保持长连接
            UpdateConnectedServer();
        } catch (KeeperException | InterruptedException e) {
            logger.error("", e);
        }
    }

    private void UpdateConnectedServer(){
        ConnectManage.getInstance().updateConnectedServer(this.dataList);
    }

    public void stop(){
        if(zookeeper!=null){
            try {
                zookeeper.close();
            } catch (InterruptedException e) {
                logger.error("", e);
            }
        }
    }
}
