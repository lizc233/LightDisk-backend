package com.star;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Slf4j
@SpringBootApplication
@EnableTransactionManagement //开启注解方式的事务管理
@EnableCaching
@EnableScheduling
@MapperScan("com.star.mapper") // 必须加这个，否则会报找不到 Mapper 的错误
public class LightDiskApplication {
    public static void main(String[] args) {
        SpringApplication.run(LightDiskApplication.class, args);
        log.info("----------------------------------------------------------");
        log.info("   (^_^) 轻量级云盘系统启动成功！可以开始卷代码了！        ");
        log.info("----------------------------------------------------------");
    }
}