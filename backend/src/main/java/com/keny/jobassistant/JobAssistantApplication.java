package com.keny.jobassistant;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.keny.jobassistant.mapper")
public class JobAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobAssistantApplication.class, args);
    }

}
