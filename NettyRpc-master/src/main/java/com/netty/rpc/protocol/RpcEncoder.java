package com.netty.rpc.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * RPC Encoder
 * @author huangyong
 */
public class RpcEncoder extends MessageToByteEncoder {

    public RpcEncoder(Class<?> genericClass) {
        super(genericClass);
    }

    @Override
    public void encode(ChannelHandlerContext ctx, Object in, ByteBuf out) throws Exception {
        byte[] data = SerializationUtil.serialize(in);
        out.writeInt(data.length);
        out.writeBytes(data);
    }
}
