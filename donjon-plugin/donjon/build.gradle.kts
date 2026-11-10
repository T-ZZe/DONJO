plugins { java }

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Si 26.3.build.+ ne résout pas, essaie 26.3.1.build.+ ou 26.1.2.build.+
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
