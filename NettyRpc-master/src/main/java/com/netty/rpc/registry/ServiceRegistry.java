package com.netty.rpc.registry;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ServiceRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ServiceRegistry.class);

    private CountDownLatch latch = new CountDownLatch(1);

    //Zookeeper集群的地址
    private String registryAddress;

    public ServiceRegistry(String registryAddress) {
        this.registryAddress = registryAddress;
    }

    //首先连接到Zookeeper集群，并且如果Zookeeper集群上没有/registry根结点的话，直接创建一个。
    //接下来，创建一个根结点，然后所有的服务器都在这个根结点下创建一个短暂节点，并且把自己的服务器ip地址和端口写入到这个节点中，
    //用来供客户端发现
    public void register(String data) {
        if (data != null) {
            ZooKeeper zk = connectServer();
            if (zk != null) {
                //Add root node if not exist
                AddRootNode(zk);
                //往zookeeper中创建 短暂顺序（EPHEMERAL_SEQUENTIAL）节点，路径为 /registry/data
                createNode(zk, data);
            }
        }
    }

    //连接到Zookeeper集群
    private ZooKeeper connectServer() {
        ZooKeeper zk = null;
        try {
            //ZooKeeper(String connectString, int sessionTimeout, Watcher watcher)
            //connectString表明主机名以及端口号，sessionTimeout表明会话超时时间，watcher对象用于接收会话事件
            //registryAddress为127.0.0.1:2181
            zk = new ZooKeeper(registryAddress, Constant.ZK_SESSION_TIMEOUT, new Watcher() {
                @Override
                public void process(WatchedEvent event) {
                    //KeeperState有如下几种状态：
                    //1.SyncConnected : The client is in the connected state - it is connected to a server in the ensemble
                    //(one of the servers specified in the host connection parameter during ZooKeeper client creation).
                    //2.Expired : The serving cluster has expired this session. The ZooKeeper client connection (the session)
                    //is no longer valid. You must create a new client connection (instantiate a new ZooKeeper instance) if you with to access the ensemble.
                    //3.Disconnected : The client is in the disconnected state - it is not connected to any server in the ensemble.
                    if (event.getState() == Event.KeeperState.SyncConnected) {
                        latch.countDown();
                    }
                }
            });
            //确保已经连接上Zookeeper集群
            latch.await();
        } catch (IOException | InterruptedException e) {
            logger.error("", e);
        }
        return zk;
    }

    private void AddRootNode(ZooKeeper zk){
        try {
            //ZK_REGISTRY_PATH 的值为 /registry，检查是否存在根节点
            Stat s = zk.exists(Constant.ZK_REGISTRY_PATH, false);
            if (s == null) {
                zk.create(Constant.ZK_REGISTRY_PATH, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        } catch (KeeperException | InterruptedException e) {
            logger.error(e.toString());
        }
    }

    private void createNode(ZooKeeper zk, String data) {
        try {
            byte[] bytes = data.getBytes();
            String path = zk.create(Constant.ZK_DATA_PATH, bytes, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
            logger.debug("create zookeeper node ({} => {})", path, data);
        } catch (KeeperException | InterruptedException e) {
            logger.error("", e);
        }
    }
}