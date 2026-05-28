package com.students.lnu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class Buildings3DBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(Buildings3DBackendApplication.class, args);
	}

}
