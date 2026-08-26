package com.rag.obsidian_rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("com.rag.obsidian_rag.config")
public class ObsidianRagApplication {

	/**
	 * Application entry point.
	 *
	 * @param args CLI arguments forwarded to Spring Boot (unused)
	 */
	public static void main(String[] args) {
		SpringApplication.run(ObsidianRagApplication.class, args);
	}

}
