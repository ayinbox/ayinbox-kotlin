import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "com.ayinbox"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

val examples: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
    runtimeClasspath += output + compileClasspath
}
kotlin.sourceSets.getByName("examples").kotlin.srcDir("examples")

tasks.register<JavaExec>("listCampaigns") {
    group = "examples"
    classpath = examples.runtimeClasspath
    mainClass.set("ListCampaignsKt")
}

tasks.register<JavaExec>("verifyWebhook") {
    group = "examples"
    classpath = examples.runtimeClasspath
    mainClass.set("VerifyWebhookKt")
}

tasks.named("check") {
    dependsOn("compileExamplesKotlin")
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    coordinates("com.ayinbox", "ayinbox-kotlin", version.toString())
    pom {
        name.set("ayinbox-kotlin")
        description.set("Kotlin client for the AY Inbox REST API")
        url.set("https://github.com/ayinbox/ayinbox-kotlin")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("ayinbox")
                name.set("AY Inbox")
                email.set("team@ayinbox.com")
            }
        }
        scm {
            url.set("https://github.com/ayinbox/ayinbox-kotlin")
            connection.set("scm:git:https://github.com/ayinbox/ayinbox-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/ayinbox/ayinbox-kotlin.git")
        }
    }
}
