package com.campushire;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CampusHire runs the BM4A platform modules (identity, tenancy, authorization, metadata, records, relationships,
 * audit, ...) together with the placement-specific modules under {@code com.campushire}. {@code @EnableAsync} makes
 * {@code @ApplicationModuleListener}s run off the request thread, after commit.
 */
@SpringBootApplication(scanBasePackages = { "com.bm4a.platform.modules", "com.campushire" })
@Modulithic(additionalPackages = "com.bm4a.platform.modules", systemName = "CampusHire")
@EnableScheduling
@EnableAsync
public class CampusHireApplication {

	public static void main(String[] args) {
		SpringApplication.run(CampusHireApplication.class, args);
	}
}
