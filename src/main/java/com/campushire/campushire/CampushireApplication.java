package com.campushire.campushire;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CampushireApplication {

	public static void main(String[] args) {
		SpringApplication.run(CampushireApplication.class, args);
	}

}
