package com.netty.rpc.services.impl;

import com.netty.rpc.server.RpcService;
import com.netty.rpc.services.PersonService;
import com.netty.rpc.services.Person;

import java.util.ArrayList;
import java.util.List;


@RpcService(PersonService.class)
public class PersonServiceImpl implements PersonService {

    @Override
    public List<Person> GetTestPerson(String name, int num) {
        List<Person> persons = new ArrayList<>(num);
        for (int i = 0; i < num; ++i) {
            persons.add(new Person(Integer.toString(i), name));
        }
        return persons;
    }
}
