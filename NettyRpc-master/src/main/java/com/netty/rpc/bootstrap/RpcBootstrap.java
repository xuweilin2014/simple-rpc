package com.netty.rpc.bootstrap;

import org.springframework.context.support.ClassPathXmlApplicationContext;

public class RpcBootstrap {

    public static void main(String[] args) {
        new ClassPathXmlApplicationContext("classpath:server-spring.xml");
    }
}
