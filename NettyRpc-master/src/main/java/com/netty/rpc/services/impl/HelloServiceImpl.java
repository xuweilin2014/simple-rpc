package com.netty.rpc.services.impl;

import com.netty.rpc.services.HelloService;
import com.netty.rpc.services.Person;
import com.netty.rpc.server.RpcService;

@RpcService(HelloService.class)
public class HelloServiceImpl implements HelloService {

    public HelloServiceImpl(){

    }

    @Override
    public String hello(String name) {
        return "Hello! " + name;
    }

    @Override
    public String hello(Person person) {
        return "Hello! " + person.getFirstName() + " " + person.getLastName();
    }

    @Override
    public String toString() {
        return "This is Hello Service";
    }
}
