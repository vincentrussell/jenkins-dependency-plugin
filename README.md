# jenkins-dependency-plugin [![Maven Central](https://img.shields.io/maven-central/v/com.github.vincentrussell/jenkins-dependency-plugin.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22com.github.vincentrussell%22%20AND%20a:%22jenkins-dependency-plugin%22) [![Build Status](https://travis-ci.org/vincentrussell/jenkins-dependency-plugin.svg?branch=master)](https://travis-ci.org/vincentrussell/jenkins-dependency-plugin)

jenkins-dependency-plugin will download a jenkins plugin (hpi) and all of it's dependencies (hpis) to a specified location.

## Maven

Add a dependency to `com.github.vincentrussell:jenkins-dependency-plugin`.

```
<dependency>
   <groupId>com.github.vincentrussell</groupId>
   <artifactId>jenkins-dependency-plugin</artifactId>
   <version>1.0</version>
</dependency>
```

## Requirements
- JDK 1.7 or higher

## Running from the command line

  The easiest way to use this plugin is to just use it from the command line.
```
mvn com.github.vincentrussell:jenkins-dependency-plugin:1.0:get -DdownloadDir=/tmp -Dartifact=junit:1.47

```
| Option | Description  |
|--|--|
| groupId | The groupId of the artifact to download. Ignored if artifact is used. |
| artifactId |  The artifactId of the artifact to download. Ignored if artifact is used. |
| version | The version of the artifact to download. Ignored if artifact is used.  |
| artifact | A string of the form groupId:artifactId:version.  |
| downloadDir | The directory where to download the plugins  |
| jenkinsPluginServerUrl | The server that stores the jenkins plugins; defaults to: https://updates.jenkins-ci.org/download/plugins  |


# Change Log

## [1.0](https://github.com/vincentrussell/jenkins-dependency-plugin/tree/jenkins-dependency-plugin-1.0) (2020-12-19)

**Enhancements:**

- Initial Release

