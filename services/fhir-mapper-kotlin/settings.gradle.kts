rootProject.name = "fhir-mapper"

// The build is deliberately self-contained rather than a subproject of a root Gradle build.
// The monorepo mixes Maven (Java), Gradle (Kotlin), Go modules and .NET; a single root build
// that tried to own all four would become the thing everyone has to understand before they
// can touch one service.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
