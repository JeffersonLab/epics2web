# epics2web
EPICS Websocket Gateway

## Introduction
Provides a websocket API to query [EPICS](http://www.aps.anl.gov/epics/) channel access.

Overview Page
![Overview](/doc/img/Overview.png?raw=true "Overview")

Test Page
![Test](/doc/img/Test.png?raw=true "Test")

Console Page
![Console](/doc/img/Console.png?raw=true "Console")

## API

## Example

```JavaScript
var options = {},
    monitoredPvs = ['mypvname1', 'mypvname2'],
    con = new jlab.epics2web.ClientConnection(options);

con.onopen = function (e) {
    console.log('Socket Connected');
    jlab.wedm.con.monitorPvs(monitoredPvs);
};

con.onupdate = function (e) {
    console.log('Update');
    console.log('PV Name: ' + e.detail.pv);
    console.log('Date: ' + e.detail.date);
    console.log('PV Value: ' + e.detail.value);
};

con.oninfo = function (e) {
    console.log('Info');
    console.log('PV Name: ' + e.detail.pv);
    console.log('Connected: ' + e.detail.connected);
    console.log('PV Type: ' + e.detail.datatype);
     
    if (typeof e.detail['enum-labels'] !== 'undefined') {
        console.log('Enum Labels: ' + e.detail['enum-labels']);
    }    
};
```

## Installation
   1. Download [Apache Tomcat](http://tomcat.apache.org/)
   1. Download epics2web.war and drop it into the webapps directory
   1. Start Tomcat and navigate your web browser to localhost:8080/epics2web

*Note:* epics2web also works and was tested with GlassFish, and presumably works with WildFly or any other Java web application server that supports web sockets.

## Configuration

This application uses the [Channel Access for Java](http://epics-jca.sourceforge.net/caj/) library.   It requires a working EPICS channel access environment with the environment variable *EPICS_CA_ADDR_LIST* set.
