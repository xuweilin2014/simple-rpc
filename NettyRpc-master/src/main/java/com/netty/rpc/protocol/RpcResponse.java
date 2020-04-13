package com.netty.rpc.protocol;

import lombok.Data;

/**
 * RPC Response
 * @author huangyong
 */
@Data
public class RpcResponse {
    private String requestId;
    private String error;
    private Object result;

    public boolean isError() {
        return error != null;
    }
}
