# epics2web
EPICS CA Web Gateway

## Introduction
Provides a JavaScript API to monitor [EPICS](http://www.aps.anl.gov/epics/) Channel Access over Web Sockets.  A caget JSON web service endpoint is also provided.

### Overview Page
![Overview](/doc/img/Overview.png?raw=true "Overview")

### Get Test Page
![GetTest](/doc/img/GetTest.png?raw=true "GetTest")

### Monitor Test Page
![MonitorTest](/doc/img/MonitorTest.png?raw=true "MonitorTest")

### Monitor Console Page
![Console](/doc/img/Console.png?raw=true "Console")

## Get API

Submit one or more parameters of name "pv" to the "caget" URL.  The response is a JSON object of the form:

Success:
```JavaScript
{data: [{name: <name>, value: <value>},...]}
```

Failure:
```JavaScript
{error: <error-reason>}
```

Note: You can optionally provide a parameter named "n" (the value can be anything) and it will be treated like "caget -n", which results in enum type PVs returning the numeric value instead of the string label.

## Monitor API

### Methods

**jlab.epics2web.ClientConnection(options)**  
Create a new ClientConnection.  
*Input:* options - see [Options](#options)  
*Output:* ClientConnection  

**jlab.epics2web.ClientConnection.monitorPvs(pvs)**  
Monitor a set of PVs.  
*Input:* pvs - array of pv names  

**jlab.epics2web.ClientConnection.clearPvs(pvs)**  
Stop monitoring a set of PVs.  
*Input:* pvs - array of pv names  

**jlab.epics2web.ClientConnection.ping()**  
Ping the server.  

**jlab.epics2web.ClientConnection.open()**  
Open the websocket connection.  

**jlab.epics2web.ClientConnection.close()**  
Close the websocket connection.  

**jlab.epics2web.ClientConnection.addEventListener(name, function)**  
Add a callback function on a named event.  
*Input:* name - the event name; see [Events](#events)  
*Input:* function - the function to call  

**jlab.epics2web.ClientConnection.onopen(function)**  
Convenience function for open event.  If more than one callback is needed use ClientConnection.addEventListener instead.  
*Input:* function - the function to call  

**jlab.epics2web.ClientConnection.onclose(function)**  
Convenience function for close event.  If more than one callback is needed use ClientConnection.addEventListener instead.  
*Input:* function - the function to call  

**jlab.epics2web.ClientConnection.onconnecting(function)**  
Convenience function for connecting event.  If more than one callback is needed use ClientConnection.addEventListener instead.  
*Input:* function - the function to call  

**jlab.epics2web.ClientConnection.onclosing(function)**  
Convenience function for closing event.  If more than one callback is needed use ClientConnection.addEventListener instead.  
*Input:* function - the function to call  

**jlab.epics2web.ClientConnection.onerror(function)**  
Convenience function for error event.  If more than one callback is needed use ClientConnection.addEventListener instead.  
*Input:* function - the function to call  

**jlab.epics2web.ClientConnection.onupdate(function)**  
Convenience function for update event.  If more than one callback is needed use ClientConnection.addEventListener instead.  
*Input:* function - the function to call  

**jlab.epics2web.ClientConnection.oninfo(function)**  
Convenience function for info event.  If more than one callback is needed use ClientConnection.addEventListener instead.  
*Input:* function - the function to call  

**jlab.epics2web.ClientConnection.onpong(function)**  
Convenience function for pong event.  If more than one callback is needed use ClientConnection.addEventListener instead.  
*Input:* function - the function to call  

### Events
- *open* - This event is triggered after the socket is open
- *close* - This event is triggered after the socket is closed
- *connecting* - This event is triggered as the socket is connecting
- *closing* - This event is triggered as the socket is closing
- *error* - This event is triggered upon socket error
- *message* - This event is triggered upon message (update/info/pong)
  - *Param:* event.type - One of 'update', 'info', 'pong'
  - *Param:* event.* - Contents based on type, see info, update, pong events
- *info* - This event is triggered upon an info message
  - *Param:* event.detail.pv - PV name
  - *Param:* event.detail.connected - true if an EPICS monitor was created
  - *Param:* event.detail.datatype - one of [Datatypes](#datatypes)
  - *Param:* event.detail['enum-labels'] - Array of enum labels or undefined if not of type DBR_ENUM 
- *update* - This event is triggered upon an update message
  - *Param:* event.detail.pv - PV name
  - *Param:* event.detail.value - the updated value
  - *Param:* event.detail.date - the update date
- *pong* - This event is triggered upon a pong message

### Datatypes
- DBR_DOUBLE
- DBR_FLOAT
- DBR_INT
- DBR_SHORT
- DBR_BYTE
- DBR_STRING
- DBR_ENUM

### Options
| Name | Description | Default |
|------|-------------|---------|
| url | Path to epics2web web socket monitor | "ws://" + window.location.host + "/epics2web/monitor" |
| autoOpen | Whether to automatically connect to socket immediately instead of waiting for open function to be called | true |
| autoReconnect | If socket is closed, will automatically reconnect after reconnectWaitMillis | true | 
| autoLivenessPingAndTimeout | Will ping the server every pingIntervalMillis and if no response in livenessTimeoutMillis then will close the socket as invalid | true | 
| autoDisplayClasses | As connect state changes will hide and show elements with corresponding state classes: ws-disconnected, ws-connecting, ws-connected | true |
| pingIntervalMillis | Milliseconds to wait between pings | 3000 |
| livenessTimoutMillis | Max milliseconds allowed for server to respond to a ping (via any message) | 2000 | 
| reconnectWaitMillis | Milliseconds to wait after socket closed before attempting reconnect | 1000 |
| chunkedRequestPvsCount | Max number of PV names to transmit in a monitor or clear command; 0 to disable chunking | 400 |
| clientName | Name of client application | window.location.href |

## Monitor Example

```JavaScript
var options = {},
    monitoredPvs = ['mypvname1', 'mypvname2'],
    con = new jlab.epics2web.ClientConnection(options);

con.onopen = function (e) {
    console.log('Socket Connected');
    con.monitorPvs(monitoredPvs);
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
   1. Download [epics2web.war](https://github.com/JeffersonLab/epics2web/releases) and drop it into the Tomcat webapps directory
   1. Copy [jar files](https://github.com/JeffersonLab/epics2web/tree/master/lib) from project lib directory to Tomcat lib directory
   1. Start Tomcat and navigate your web browser to localhost:8080/epics2web

*Note:* epics2web also works and was tested with GlassFish, and presumably works with WildFly or any other Java web application server that supports Web Sockets.

## Build from Source
```
git clone https://github.com/JeffersonLab/epics2web
cd epics2web
gradlew war
```

## Docker
```
docker run --rm -it -e EPICS_CA_ADDR_LIST=softioc --name epics2web slominskir/epics2web
```

## Configuration

This application uses the [Channel Access for Java](http://epics-jca.sourceforge.net/caj/) library.   It requires a working EPICS channel access environment with the environment variable *EPICS_CA_ADDR_LIST* set.  See Also: [Advanced Configuration](https://github.com/JeffersonLab/epics2web/wiki/Advanced-Configuration).

## See Also
- [PV Monitor Runchart](https://github.com/JeffersonLab/runchart)
- [Web Extensible Display Manager (wedm)](https://github.com/JeffersonLab/wedm)
- [Similar Projects](https://github.com/JeffersonLab/epics2web/wiki/Similar-Projects)
- [Technical Notes](https://github.com/JeffersonLab/epics2web/wiki/Technical-Notes)
- [Testing Suite](https://github.com/JeffersonLab/jca-test-suite)
