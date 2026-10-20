plugins {
	id("java")
	id("org.springframework.boot") version "3.3.4" // Use your preferred Spring Boot 3.x version
	id("io.spring.dependency-management") version "1.1.6"
}

group = "nbgsmk.msr"
version = "1.0-SNAPSHOT"

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(21)) // Target modern Java 21 LTS
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Core Spring Boot Web starter
	implementation("org.springframework.boot:spring-boot-starter-web")

	// The core WebSocket infrastructure required for your real-time channel
	implementation("org.springframework.boot:spring-boot-starter-websocket")

	// Test suite (Optional but recommended)
	testImplementation("org.springframework.boot:spring-boot-starter-test")


	testImplementation(platform("org.junit:junit-bom:6.0.0"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
	useJUnitPlatform()
}