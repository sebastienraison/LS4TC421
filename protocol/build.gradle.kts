dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

tasks.test {
    // The reference frames are the single source of truth for the frame tests.
    systemProperty("ls4tc421.vectors", rootProject.file("spec/vectors.json").absolutePath)
}
