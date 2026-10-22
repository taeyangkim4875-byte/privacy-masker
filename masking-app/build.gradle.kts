import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(project(":masking-core"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---------------------------------------------------------------
// 통합 테스트(@Tag("integration")): 실제 MariaDB 필요. 기본 test 에서는 제외
//   PIIMASK_IT_DB_URL / PIIMASK_IT_DB_USER / PIIMASK_IT_DB_PASSWORD 설정 후
//   ./gradlew :masking-app:integrationTest -PskipUi
// ---------------------------------------------------------------
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("integration") }
}

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "실제 MariaDB 대상 배치 통합 테스트"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.named("test"))
    outputs.upToDateWhen { false }
    // 메타 DB·마스터키·로그를 매번 새로 만든다
    doFirst { delete(layout.buildDirectory.dir("it-data")) }
}

// ---------------------------------------------------------------
// React 화면 빌드를 jar 안(static/)에 포함
//   ./gradlew bootRun -PskipUi  → 화면 빌드 생략(백엔드만 빠르게 재기동할 때)
// ---------------------------------------------------------------
val uiDir = rootProject.file("masking-ui")
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val npm = if (isWindows) "npm.cmd" else "npm"
val skipUi = project.hasProperty("skipUi")

val uiInstall by tasks.registering(Exec::class) {
    group = "ui"
    description = "npm ci (node_modules 가 없을 때만)"
    workingDir = uiDir
    commandLine(npm, "ci", "--no-audit", "--no-fund")
    onlyIf { !skipUi && !uiDir.resolve("node_modules").exists() }
}

val uiBuild by tasks.registering(Exec::class) {
    group = "ui"
    description = "React 화면 빌드 → masking-ui/dist"
    dependsOn(uiInstall)
    workingDir = uiDir
    commandLine(npm, "run", "build")
    inputs.dir(uiDir.resolve("src"))
    inputs.file(uiDir.resolve("index.html"))
    inputs.file(uiDir.resolve("package.json"))
    inputs.file(uiDir.resolve("vite.config.ts"))
    inputs.file(uiDir.resolve("tsconfig.json"))
    outputs.dir(uiDir.resolve("dist"))
    onlyIf { !skipUi }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(uiBuild)
    from(uiDir.resolve("dist")) {
        into("static")
    }
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // 메타 DB(./data)가 프로젝트 루트에 생기도록
    workingDir = rootProject.projectDir
}
