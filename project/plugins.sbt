addSbtPlugin("com.timushev.sbt" % "sbt-updates"        % "0.6.3")
addSbtPlugin("org.scalameta"    % "sbt-scalafmt"       % "2.5.2")
addSbtPlugin("com.github.sbt"   % "sbt-ci-release"     % "1.5.12")
addSbtPlugin("com.github.sbt"   % "sbt-github-actions" % "0.24.0")
addSbtPlugin("ch.epfl.scala"    % "sbt-scalafix"       % "0.12.1")

libraryDependencies += "org.typelevel" %% "scalac-options" % "0.1.5"
