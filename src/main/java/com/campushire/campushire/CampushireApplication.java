package com.campushire.campushire;

import com.campushire.campushire.modules.auth.config.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AuthProperties.class)
public class CampushireApplication {

	public static void main(String[] args) {
		SpringApplication.run(CampushireApplication.class, args);
	}
}