// JVM-only fake controller used by tests. Never shipped inside the app.
dependencies {
    api(project(":protocol"))
    testImplementation(libs.junit)
}
