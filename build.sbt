ThisBuild / scalaVersion                  := "2.12.21"
ThisBuild / organization                  := "com.alejandrohdezma"
ThisBuild / pluginCrossBuild / sbtVersion := "1.4.0"
ThisBuild / versionPolicyIntention        := Compatibility.None

addCommandAlias("ci-test", "fix --check; versionPolicyCheck; mdoc; scripted")
addCommandAlias("ci-docs", "github; mdoc; headerCreateAll")
addCommandAlias("ci-publish", "versionCheck; github; ci-release")

lazy val documentation = project
  .enablePlugins(MdocPlugin)
  .settings(mdocVariables += "AT_VERSION" -> s"@v${mdocVariables.value("VERSION")}")

lazy val `sbt-changesets` = module
  .enablePlugins(SbtPlugin)
  .settings(scriptedLaunchOpts += s"-Dplugin.version=${version.value}")
  .settings(scriptedBufferLog := true)
  .settings(scriptedBatchExecution := true)
