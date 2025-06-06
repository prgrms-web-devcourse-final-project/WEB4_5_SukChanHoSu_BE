package com.NBE4_5_SukChanHoSu.BE;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
//import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
//@EnableElasticsearchRepositories(basePackages = "com.NBE4_5_SukChanHoSu.BE.domain.movie.repository")
public class SukChanHoSuApplication {

    public static void main(String[] args) {
        SpringApplication.run(SukChanHoSuApplication.class, args);
        System.out.println("AWS Access Key from Env: " + System.getenv("AWS.ACCESS_KEY"));
        System.out.println("AWS Secret Key from Env: " + System.getenv("AWS.SECRET_KEY"));

    }
}
