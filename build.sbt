ThisBuild / scalaVersion                  := "2.12.21"
ThisBuild / crossScalaVersions            := Seq(scalaVersion.value, "3.8.4")
ThisBuild / organization                  := "com.alejandrohdezma"
ThisBuild / pluginCrossBuild / sbtVersion := scalaVersion.value.on(2)("1.12.12").getOrElse("2.0.0")
ThisBuild / versionPolicyIntention        := Compatibility.BinaryAndSourceCompatible

addCommandAlias("ci-test", "fix --check; +versionPolicyCheck; mdoc; +test; +publishLocal; +scripted")
addCommandAlias("ci-docs", "github; mdoc; headerCreateAll")
addCommandAlias("ci-publish", "versionCheck; github; ci-release")

lazy val documentation = project
  .enablePlugins(MdocPlugin)
  .settings(mdocVariables += "AT_VERSION" -> s"@v${mdocVariables.value("VERSION")}")

lazy val `sbt-changesets` = module
  .enablePlugins(SbtPlugin)
  .settings(scriptedLaunchOpts += s"-Dplugin.version=${version.value}")
  .settings(scriptedLaunchOpts += "-DSNAPSHOT_SUFFIX=abc123")
  .settings(scriptedLaunchOpts += s"-Dsbt.boot.directory=${sys.props("user.home")}/.sbt/boot")
  .settings(scriptedBufferLog := true)
  .settings(scriptedBatchExecution := true)
