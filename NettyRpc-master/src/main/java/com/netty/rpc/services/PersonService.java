package com.netty.rpc.services;

import java.util.List;

public interface PersonService {
    List<Person> GetTestPerson(String name, int num);
}
