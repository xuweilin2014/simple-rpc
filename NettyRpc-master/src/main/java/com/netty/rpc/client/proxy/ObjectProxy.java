package com.netty.rpc.client.proxy;

import com.netty.rpc.client.ConnectManage;
import com.netty.rpc.client.RPCFuture;
import com.netty.rpc.client.RpcClientHandler;
import com.netty.rpc.protocol.RpcRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Created by luxiaoxun on 2016-03-16.
 */
public class ObjectProxy<T> implements InvocationHandler, IAsyncObjectProxy {
    private static final Logger LOGGER = LoggerFactory.getLogger(ObjectProxy.class);
    private Class<T> clazz;

    public ObjectProxy(Class<T> clazz) {
        this.clazz = clazz;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (Object.class == method.getDeclaringClass()) {
            String name = method.getName();
            if ("equals".equals(name)) {
                return proxy == args[0];
            } else if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            } else if ("toString".equals(name)) {
                return proxy.getClass().getName() + "@" +
                        Integer.toHexString(System.identityHashCode(proxy)) +
                        ", with InvocationHandler " + this;
            } else {
                throw new IllegalStateException(String.valueOf(method));
            }
        }

        RpcRequest request = new RpcRequest();
        request.setRequestId(UUID.randomUUID().toString());
        // getDeclaringClass 方法返回声明此 Method 对象表示的方法的类的Class对象。
        request.setClassName(method.getDeclaringClass().getName());
        request.setMethodName(method.getName());
        request.setParameterTypes(method.getParameterTypes());
        request.setParameters(args);
        // Debug
        LOGGER.debug(method.getDeclaringClass().getName());
        LOGGER.debug(method.getName());
        for (int i = 0; i < method.getParameterTypes().length; ++i) {
            LOGGER.debug(method.getParameterTypes()[i].getName());
        }
        for (int i = 0; i < args.length; ++i) {
            LOGGER.debug(args[i].toString());
        }
        // 选择与服务端相连的一个 handler，通过此 handler 把要发送的 RpcRequest 发到 Rpc 服务器
        // ConnectManage.getInstance().chooseHandler()：一个简单的负载均衡方法，找到应该调用的服务器。
        RpcClientHandler handler = ConnectManage.getInstance().chooseHandler();
        RPCFuture rpcFuture = handler.sendRequest(request);

        // 调用RPCFuture的get方法，阻塞在get方法上，直到获得服务端返回的响应response（也就是方法调用的结果）
        return rpcFuture.get();
    }

    @Override
    public RPCFuture call(String funcName, Object... args) {
        RpcClientHandler handler = ConnectManage.getInstance().chooseHandler();
        RpcRequest request = createRequest(this.clazz.getName(), funcName, args);
        RPCFuture rpcFuture = handler.sendRequest(request);
        return rpcFuture;
    }

    private RpcRequest createRequest(String className, String methodName, Object[] args) {
        RpcRequest request = new RpcRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setClassName(className);
        request.setMethodName(methodName);
        request.setParameters(args);

        Class[] parameterTypes = new Class[args.length];
        // Get the right class type
        for (int i = 0; i < args.length; i++) {
            parameterTypes[i] = getClassType(args[i]);
        }
        request.setParameterTypes(parameterTypes);

        LOGGER.debug(className);
        LOGGER.debug(methodName);
        for (int i = 0; i < parameterTypes.length; ++i) {
            LOGGER.debug(parameterTypes[i].getName());
        }
        for (int i = 0; i < args.length; ++i) {
            LOGGER.debug(args[i].toString());
        }

        return request;
    }

    private Class<?> getClassType(Object obj) {
        Class<?> classType = obj.getClass();
        String typeName = classType.getName();
        switch (typeName) {
            case "java.lang.Integer":
                return Integer.TYPE;
            case "java.lang.Long":
                return Long.TYPE;
            case "java.lang.Float":
                return Float.TYPE;
            case "java.lang.Double":
                return Double.TYPE;
            case "java.lang.Character":
                return Character.TYPE;
            case "java.lang.Boolean":
                return Boolean.TYPE;
            case "java.lang.Short":
                return Short.TYPE;
            case "java.lang.Byte":
                return Byte.TYPE;
        }

        return classType;
    }

}
