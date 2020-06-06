package com.netty.rpc.services;

public interface HelloService {
    String hello(String name);

    String hello(Person person);
}
