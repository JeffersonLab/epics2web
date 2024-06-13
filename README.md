# epics2web [![Java CI with Gradle](https://github.com/JeffersonLab/epics2web/actions/workflows/ci.yml/badge.svg)](https://github.com/JeffersonLab/epics2web/actions/workflows/ci.yml) [![Docker](https://img.shields.io/docker/v/jeffersonlab/epics2web?sort=semver&label=DockerHub)](https://hub.docker.com/r/jeffersonlab/epics2web)
A gateway server and accompanying JavaScript client API to monitor EPICS Channel Access over the web.

![MonitorTest](https://github.com/JeffersonLab/epics2web/raw/main/doc/img/MonitorTest.png?raw=true "MonitorTest")

---
- [Overview](https://github.com/JeffersonLab/epics2web#overview)
- [Quick Start with Compose](https://github.com/JeffersonLab/epics2web#quick-start-with-compose)
- [Install](https://github.com/JeffersonLab/epics2web#build)
- [API](https://github.com/JeffersonLab/epics2web#api)
- [Configure](https://github.com/JeffersonLab/epics2web#configure)
- [Build](https://github.com/JeffersonLab/epics2web#build) 
- [Test](https://github.com/JeffersonLab/epics2web#test)
- [Release](https://github.com/JeffersonLab/epics2web#release)
- [See Also](https://github.com/JeffersonLab/epics2web#see-also)
---

## Overview
The epics2web application allows users to monitor [EPICS](http://www.aps.anl.gov/epics/) PVs from the web using Web Sockets and a REST web service endpoint.  The application leverages the Java [JCA](https://github.com/epics-base/jca) library and is designed to run on Apache Tomcat.

## Quick Start with Compose 
1. Grab project
```
git clone https://github.com/JeffersonLab/epics2web
cd epics2web
```
2. Launch [Compose](https://github.com/docker/compose)
```
docker compose up
```
3. Monitor test PV via web browser   

http://localhost:8080/epics2web/test-camonitor

PV name: `HELLO`

**See**: [Docker Compose Strategy](https://gist.github.com/slominskir/a7da801e8259f5974c978f9c3091d52c)

## Install
   1. Download Java 8+
   1. Download [Apache Tomcat 7, 8, or 9](http://tomcat.apache.org/)
   1. Download [epics2web.war](https://github.com/JeffersonLab/epics2web/releases) and drop it into the Tomcat webapps directory
   1. Start Tomcat and navigate your web browser to localhost:8080/epics2web

**Note:** epics2web also works and was tested with GlassFish, and presumably works with WildFly or any other Java web application server that supports Web Sockets.

**Note:** The dependency jars are included in the _war_ file that is generated by the build.  You can copy the [jar files](https://github.com/JeffersonLab/epics2web/tree/master/lib) from project lib directory to the Tomcat lib directory and change the build.gradle script to use _providedCompile_ instead of _implementation_ if you'd prefer to include the dependencies that way.

## API

[API Reference](https://github.com/JeffersonLab/epics2web/wiki/API-Reference)

## Configure

This application uses the [Java Channel Access](https://github.com/epics-base/jca) library.   It requires a working EPICS channel access environment with the environment variable *EPICS_CA_ADDR_LIST* set.  See Also: [Advanced Configuration](https://github.com/JeffersonLab/epics2web/wiki/Advanced-Configuration).

### Logging
This app is designed to run on Tomcat so [Tomcat logging configuration](https://tomcat.apache.org/tomcat-9.0-doc/logging.html) applies.  We use the built-in JVM logging library, which Tomcat uses with some slight modifications to support separate classloaders.  In the past we bundled an application [logging.properites](https://github.com/JeffersonLab/epics2web/blob/956894699ef1b303907a04720aeb50260ffa72b1/src/main/resources/logging.properties) inside the epics2web.war file.  We no longer do that because it then appears to require repackaging/rebuilding a new version of the app to modify the logging config as the app bundled config overrides the global Tomcat config at conf/logging.properties.  The recommend logging strategy is to now make configuration in the global Tomcat config so as to make it easy to modify logging levels.  An app specific handler can be created.  The global configuration location is generally set by the Tomcat default start script via JVM system properties.  The system properties should look something like: 
- `-Djava.util.logging.config.file=/usr/share/tomcat/conf/logging.properties`
- `-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager`

The contents of the logging.properties should be modfied to look something like:
```
handlers = 1catalina.org.apache.juli.FileHandler, 2localhost.org.apache.juli.FileHandler, 3manager.org.apache.juli.FileHandler, 4host-manager.org.apache.juli.FileHandler, 5epics2web.org.apache.juli.FileHandler, java.util.logging.ConsoleHandler

5epics2web.org.apache.juli.FileHandler.level = FINEST
5epics2web.org.apache.juli.FileHandler.directory = ${catalina.base}/logs
5epics2web.org.apache.juli.FileHandler.prefix = epics2web.

org.jlab.epics2web.handlers = 5epics2web.org.apache.juli.FileHandler
org.jlab.epics2web.level = ALL
```

**Note**: This example is for older versions of Tomcat as newer version use an AsyncFileHandler.  Refer to your logging configuration guide for your version of Tomcat.

## Build
This project is built with [Java 17](https://adoptium.net/) (compiled to Java 8 bytecode), and uses the [Gradle 7](https://gradle.org/) build tool to automatically download dependencies and build the project from source:

```
git clone https://github.com/JeffersonLab/epics2web
cd epics2web
gradlew build
```
**Note**: If you do not already have Gradle installed, it will be installed automatically by the wrapper script included in the source

**Note for JLab On-Site Users**: Jefferson Lab has an intercepting [proxy](https://gist.github.com/slominskir/92c25a033db93a90184a5994e71d0b78)

**See**: [Docker Development Quick Reference](https://gist.github.com/slominskir/a7da801e8259f5974c978f9c3091d52c#development-quick-reference)

## Test
```
docker compose -f build.yaml up
```
Wait for containers to start then:
```
gradlew integrationTest
```
## Release
1. Bump the version number and release date in build.gradle and commit and push to GitHub (using [Semantic Versioning](https://semver.org/)).
2. Create a new release on the GitHub [Releases](https://github.com/JeffersonLab/epics2web/releases) page corresponding to same version in build.gradle (Enumerate changes and link issues).  Attach war file for users to download.
3. Build and publish a new Docker image [from the GitHub tag](https://gist.github.com/slominskir/a7da801e8259f5974c978f9c3091d52c#8-build-an-image-based-of-github-tag).  GitHub is configured to do this automatically on git push of semver tag (typically part of GitHub release) or the [Publish to DockerHub](https://github.com/JeffersonLab/epics2web/actions/workflows/docker-publish.yml) action can be manually triggered after selecting a tag.
4. Bump and commit quick start [image version](https://github.com/JeffersonLab/epics2web/blob/main/docker-compose.override.yml)

## See Also
- [Web Extensible Display Manager (wedm)](https://github.com/JeffersonLab/wedm)
- [Web Archive Viewer and Expositor (WAVE)](https://github.com/JeffersonLab/wave)
- [PV Monitor Runchart](https://github.com/JeffersonLab/runchart)
- [Similar Projects](https://github.com/JeffersonLab/epics2web/wiki/Similar-Projects)
- [Technical Notes](https://github.com/JeffersonLab/epics2web/wiki/Technical-Notes)
- [Testing Suite](https://github.com/JeffersonLab/jca-test-suite)
