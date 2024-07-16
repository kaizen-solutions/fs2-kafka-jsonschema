import org.typelevel.scalacoptions.{ScalaVersion, ScalacOptions}

inThisBuild {
  val scala3 = "3.3.3"

  Seq(
    scalaVersion := scala3,
    scalacOptions ++= ScalacOptions
      .tokensForVersion(
        ScalaVersion.fromString(scala3).right.get,
        Set(
          ScalacOptions.encoding("utf8"),
          ScalacOptions.feature,
          ScalacOptions.unchecked,
          ScalacOptions.deprecation,
          ScalacOptions.warnValueDiscard,
          ScalacOptions.warnDeadCode,
          ScalacOptions.release("17"),
          ScalacOptions.privateKindProjector
        )
      ),
    versionScheme              := Some("early-semver"),
    githubWorkflowJavaVersions := List(JavaSpec.temurin("17")),
    githubWorkflowTargetTags ++= Seq("v*"),
    githubWorkflowPublishTargetBranches := Seq(
      RefPredicate.StartsWith(Ref.Tag("v")),
      RefPredicate.Equals(Ref.Branch("main"))
    ),
    githubWorkflowPublish := Seq(
      WorkflowStep.Sbt(
        commands = List("ci-release"),
        name = Some("Publish project"),
        env = Map(
          "PGP_PASSPHRASE"    -> "${{ secrets.PGP_PASSPHRASE }}",
          "PGP_SECRET"        -> "${{ secrets.PGP_SECRET }}",
          "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
          "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
        )
      )
    ),
    developers := List(
      Developer("calvinlfer", "Calvin Fernandes", "cal@kaizen-solutions.io", url("https://www.kaizen-solutions.io"))
    ),
    licenses               := List("MIT" -> url("https://opensource.org/licenses/MIT")),
    organization           := "io.kaizen-solutions",
    organizationName       := "kaizen-solutions",
    homepage               := Some(url("https://www.kaizen-solutions.io")),
    sonatypeCredentialHost := "s01.oss.sonatype.org",
    sonatypeProfileName    := "io.kaizen-solutions",
    sonatypeRepository     := "https://s01.oss.sonatype.org/service/local",
    sonatypeCredentialHost := "s01.oss.sonatype.org"
  )
}

resolvers ++= Seq("confluent".at("https://packages.confluent.io/maven/"))

lazy val root =
  project
    .in(file("."))
    .settings(
      name := "fs2-kafka-jsonschema",
      libraryDependencies ++= {
        val circe     = "io.circe"
        val fd4s      = "com.github.fd4s"
        val tapir     = "com.softwaremill.sttp.tapir"
        val fs2KafkaV = "3.5.1"
        val tapirV    = "1.10.13"

        Seq(
          fd4s                            %% "fs2-kafka"                    % fs2KafkaV,
          tapir                           %% "tapir-json-pickler"           % tapirV,
          tapir                           %% "tapir-apispec-docs"           % tapirV,
          "com.softwaremill.sttp.apispec" %% "jsonschema-circe"             % "0.10.0",
          "org.scala-lang.modules"        %% "scala-collection-compat"      % "2.12.0",
          "org.typelevel"                 %% "munit-cats-effect"            % "2.0.0-M3" % Test,
          "com.dimafeng"                  %% "testcontainers-scala-munit"   % "0.41.4"   % Test,
          "ch.qos.logback"                 % "logback-classic"              % "1.5.6"    % Test,
          "io.confluent"                   % "kafka-json-schema-serializer" % "7.6.1"
        )
      }
    )
