package org.openphc.cce.admin;

import org.openphc.cce.admin.config.RequiredSettingsCheck;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CceAdminServiceApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(CceAdminServiceApplication.class);
		app.addListeners(new RequiredSettingsCheck());
		app.run(args);
	}
}
