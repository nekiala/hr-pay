plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "cd.hrpay"
version = "0.9.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Sessions in PostgreSQL, so several API servers share them and a restart signs no one out.
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
    // PDF payslips.
    implementation("com.github.librepdf:openpdf:3.0.5")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("load")
    }
}

tasks.withType<Test> {
    // spring-security-test swaps CsrfFilter's final field; Java 26 warns unless this is allowed.
    jvmArgs("--enable-final-field-mutation=ALL-UNNAMED")
}

// Version and build time at /actuator/info and on the System page.
springBoot {
    buildInfo()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "hr-pay.jar"
}

// The load test (5,000 employees, 1,000 check-ins): ./gradlew loadTest. It adds people to the test database.
tasks.register<Test>("loadTest") {
    description = "Payroll of 5,000 employees and 1,000 check-ins against the test database."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("load")
    }
    maxHeapSize = "2g"
    // ./gradlew loadTest -Pload.employees=1000 -Pload.checkIns=200 for a quicker run.
    listOf("load.employees", "load.checkIns").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
    testLogging {
        showStandardStreams = true
    }
}

// The provider's licence tool (keys, issue), run on the provider's computer:
// ./gradlew -q licence --args="keys ~/.hrpay-licence"
tasks.register<JavaExec>("licence") {
    description = "Makes the provider's licence keys or signs a licence file"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("cd.hrpay.platform.LicenceTool")
    standardInput = System.`in`
}
