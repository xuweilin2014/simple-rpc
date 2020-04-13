package com.netty.rpc.protocol;

import lombok.Data;

/**
 * RPC Request
 * @author huangyong
 */
@Data
public class RpcRequest {
    private String requestId;
    private String className;
    private String methodName;
    private Class<?>[] parameterTypes;
    private Object[] parameters;

}