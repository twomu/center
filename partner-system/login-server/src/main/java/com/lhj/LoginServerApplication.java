package com.lhj;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.hystrix.EnableHystrix;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class LoginServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoginServerApplication.class, args);
    }

}
