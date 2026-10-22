// 공통 설정. 계획서 기준 운영 타깃은 Java 25 이며, 로컬 개발 편의를 위해 21 이상이면 빌드되도록 release 21 로 둔다.
subprojects {
    apply(plugin = "java")

    group = "com.piimask"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
