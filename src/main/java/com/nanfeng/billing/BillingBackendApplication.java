package com.nanfeng.billing;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.nanfeng.billing.mapper")
@SpringBootApplication
public class BillingBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingBackendApplication.class, args);
    }
}
