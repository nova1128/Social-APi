package com.grid07.social_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class SocialApiApplication {
	public static void main(String[] args) {
		// Fix for PostgreSQL not recognizing "Asia/Calcutta" (deprecated JVM timezone name)
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(SocialApiApplication.class, args);
	}
}