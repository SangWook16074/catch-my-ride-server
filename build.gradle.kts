plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.hansw"
version = "0.5.0"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-actuator") // /actuator/health — compose healthcheck·UptimeRobot 감시용
	implementation("org.springframework.boot:spring-boot-starter-jdbc") // CommuteSetting·BoardingFeedback 저장 (S-1/S-3)
	runtimeOnly("org.postgresql:postgresql")
	testRuntimeOnly("com.h2database:h2") // 테스트는 인메모리 H2(PostgreSQL 모드) — src/test/resources/application.yml
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("tools.jackson.dataformat:jackson-dataformat-xml") // TOPIS·GBIS는 XML 응답
	implementation("org.webjars:swagger-ui:5.25.3") // /swagger-ui.html — openapi.yaml(API.md 계약) 뷰어. springdoc은 Boot 4 미지원이라 정적 서빙
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("tools.jackson.dataformat:jackson-dataformat-yaml") // openapi.yaml 문법 검증(ApiDocsEndpointTest)
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
