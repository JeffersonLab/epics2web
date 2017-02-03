# epics2web
EPICS Websocket Gateway

## Introduction
Provides a websocket API to query [EPICS](http://www.aps.anl.gov/epics/) channel access.

## API


## Installation
   1. Download [Apache Tomcat](http://tomcat.apache.org/)
   1. Download epics2web.war and drop it into the webapps directory
   1. Start Tomcat and navigate your web browser to localhost:8080/epics2web

*Note:* epics2web also works and was tested with GlassFish, and presumably works with WildFly or any other Java web application server that supports web sockets.

## Configuration

This application uses the [Channel Access for Java](http://epics-jca.sourceforge.net/caj/) library.   It requires a working EPICS channel access environment with the environment variable *EPICS_CA_ADDR_LIST* set.
