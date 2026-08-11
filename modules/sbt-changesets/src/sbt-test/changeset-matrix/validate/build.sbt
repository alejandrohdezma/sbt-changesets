ThisBuild / organization       := "com.example"
ThisBuild / scalaVersion       := "3.3.7"
ThisBuild / crossScalaVersions := Seq("3.3.7", "2.13.18")

lazy val `module-a` = module

lazy val `module-b` = module.settings(crossScalaVersions := Seq("3.3.7"))

lazy val `module-c` = module.settings(changesetAlwaysBump := true)

lazy val `module-d` = module.settings(crossPaths := false, crossScalaVersions := Seq("3.3.7"))

lazy val `module-e` = module.settings(changesetCoordinate ~= { _ + " % \"test\"" })

// Publishes under an artifactId different from its project name; the coordinate must track `moduleName`.
lazy val `module-f` = module.settings(moduleName := "module-f-renamed")
