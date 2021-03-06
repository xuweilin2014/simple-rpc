package com.netty.rpc.server;

import com.netty.rpc.protocol.RpcDecoder;
import com.netty.rpc.protocol.RpcRequest;
import com.netty.rpc.protocol.RpcResponse;
import com.netty.rpc.protocol.RpcEncoder;
import com.netty.rpc.registry.ServiceRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.apache.commons.collections4.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * RPC Server
 *
 * 1.RpcServer实现了 ApplicationContextAware 接口：
 * 开发者只要在web.xml中配置一个Listener，该Listener将会负责初始化Spring容器，MVC框架可以直接调用Spring容器中的Bean，
 * 无需访问Spring容器本身。在这种情况下，容器中的Bean处于容器管理下，无需主动访问容器，只需接受容器的依赖注入即可。
 *
 * 但在某些特殊的情况下，Bean需要实现某个功能，但该功能必须借助于Spring容器才能实现，此时就必须让该Bean先获取Spring容器，
 * 然后借助于Spring容器实现该功能。为了让Bean获取它所在的Spring容器，可以让该Bean实现ApplicationContextAware接口。
 *
 * Spring容器会检测容器中的所有Bean，如果发现某个Bean实现了ApplicationContextAware接口，Spring容器会在创建该Bean之后，
 * 自动调用该Bean的setApplicationContextAware()方法，调用该方法时，会将容器本身作为参数传给该方法。
 *
 * 2.RpcServer实现了 InitializingBean 接口：
 * 当BeanFactory将bean创建成功，并设置完成所有它们的属性后，我们想在这个时候去做出自定义的反应，比如检查一些强制属性是否被设置成功，
 * 这个时候我们可以让我们的bean的class实现InitializingBean接口，以被触发。
 *
 * 该接口仅有一个afterPropertiesSet方法，该方法会在bean的所有属性被设置完成（包括各种Aware的设置，如BeanFactoryAware，
 * ApplicationContextAware等）后由容器（BeanFactory）调用。此方法允许bean实例在设置了所有bean属性后执行总体配置的验证和最终初始化
 *
 * ******************************************** 总结 ****************************************************
 *
 * RpcServer这个bean，在Spring IoC容器启动的时候，被初始化，在初始化的过程中，它主要完成两个工作：
 * 1.将带有RpcService注解的bean，保存到handlerMap中，Key为带有注解的bean所实现的接口，而Value为bean本身
 * 2.让RpcServer开始监听127.0.0.1:18866这个ip地址和端口号，同时连接到Zookeeper集群，创建一个节点，并把自己的地址写入到Zookeeper节点中
 */
public class RpcServer implements ApplicationContextAware, InitializingBean {

    private static final Logger logger = LoggerFactory.getLogger(RpcServer.class);

    private String serverAddress;
    private ServiceRegistry serviceRegistry;

    private Map<String, Object> handlerMap = new HashMap<>();
    private static ThreadPoolExecutor threadPoolExecutor;

    private EventLoopGroup bossGroup = null;
    private EventLoopGroup workerGroup = null;

    public RpcServer(String serverAddress) {
        this.serverAddress = serverAddress;
    }

    public RpcServer(String serverAddress, ServiceRegistry serviceRegistry) {
        this.serverAddress = serverAddress;
        this.serviceRegistry = serviceRegistry;
    }

    /**
     * 获取Spring容器中所有带有RpcService注解的Bean
     * 并且以注解的value值（即服务类所实现的接口）作为key，Bean作为object放入到handlerMap中为value
     */
    @Override
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        Map<String, Object> serviceBeanMap = ctx.getBeansWithAnnotation(RpcService.class);
        if (MapUtils.isNotEmpty(serviceBeanMap)) {
            for (Object serviceBean : serviceBeanMap.values()) {
                String interfaceName = serviceBean.getClass().getAnnotation(RpcService.class).value().getName();
                logger.info("Loading service: {}", interfaceName);
                handlerMap.put(interfaceName, serviceBean);
            }
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        start();
    }

    public void stop() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    //提交任务到RpcServer中的线程池，并且线程池是唯一的
    public static void submit(Runnable task) {
        if (threadPoolExecutor == null) {
            synchronized (RpcServer.class) {
                if (threadPoolExecutor == null) {
                    threadPoolExecutor = new ThreadPoolExecutor(16, 16, 600L,
                            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(65536));
                }
            }
        }
        threadPoolExecutor.submit(task);
    }

    public RpcServer addService(String interfaceName, Object serviceBean) {
        if (!handlerMap.containsKey(interfaceName)) {
            logger.info("Loading service: {}", interfaceName);
            handlerMap.put(interfaceName, serviceBean);
        }

        return this;
    }

    /**
     * 启动 Rpc 服务器，并且连接到 Zookeeper 集群，然后把此服务器的地址注册到 Zookeeper 集群的 /registry 地址中
     */
    public void start() throws Exception {
        if (bossGroup == null && workerGroup == null) {
            bossGroup = new NioEventLoopGroup();
            workerGroup = new NioEventLoopGroup(8);
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel channel) throws Exception {
                            channel.pipeline()
                                    .addLast(new LengthFieldBasedFrameDecoder(65536, 0, 4,
                                            0, 0))
                                    .addLast(new RpcDecoder(RpcRequest.class))
                                    .addLast(new RpcEncoder(RpcResponse.class))
                                    .addLast(new RpcServerHandler(handlerMap));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            String[] array = serverAddress.split(":");
            String host = array[0];
            int port = Integer.parseInt(array[1]);
            // Netty绑定到 127.0.0.1:18866 地址，并且进行监听，并且将此地址注册到 Zookeeper 集群中
            ChannelFuture future = bootstrap.bind(host, port).sync();

            future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture) throws Exception {
                    if (channelFuture.isSuccess()){
                        logger.info("Server started on port {}", port);
                        submit(new Runnable() {
                            @Override
                            public void run() {
                                if (serviceRegistry != null) {
                                    serviceRegistry.register(serverAddress);
                                }
                                /*
                                 * 这里要把future.channel().closeFuture().sync()放入到线程池中去执行，是为了防止抛出BlockingOperationException，
                                 * 即死锁异常。
                                 * 如果用户操作调用了sync或者await方法，会在对应的future对象上阻塞用户线程，例如future.channel().closeFuture().sync()，
                                 * 而最终触发future对象的notify动作都是通过eventLoop线程轮询任务完成的，例如对关闭的sync，因为不论是用户直接关闭或者eventLoop的轮询状态关闭，
                                 * 都会在eventLoop的线程内完成notify动作，所以不要在IO线程内调用future对象的sync或者await方法，这样会造成死锁，
                                 * 即该线程上的一个任务等待该线程上的其他任务唤醒自己
                                 */
                                try {
                                    future.channel().closeFuture().sync();
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                    }
                }
            });
        }
    }

}
