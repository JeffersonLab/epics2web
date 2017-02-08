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

### Methods

**jlab.epics2web.ClientConnection(options)**  
Create a new ClientConnection  
*Input*: options - see [Options](#options)  
*Output:* ClientConnection  

**jlab.epics2web.monitorPvs(pvs)**  
Monitor a set of PVs  
*Input:* pvs - array of pv names  

**jlab.epics2web.clearPvs(pvs)**  
Stop monitoring a set of PVs  
*Input:* pvs - array of pv names  

**jlab.epics2web.ping()**  
Ping the server  

**jlab.epics2web.open()**  
Open the websocket connection  

**jlab.epics2web.close()**  
Close the websocket connection  

### Events
- *open* - This event is triggered after the socket is open
- *close* - This event is triggered after the socket is closed
- *connecting* - This event is triggered as the socket is connecting
- *closing* - This event is triggered as the socket is closing
- *error* - This event is triggered upon socket error
- *message* - This event is triggered upon message (update/info/pong)
- *info* - This event is triggered upon an info message
- *update* - This event is triggered upon an update message
- *pong* - This event is triggered upon a pong message

### Options
| Name | Description | Default |
|------|-------------|---------|
| url | Path to epics2web web socket monitor | "ws://" + window.location.host + "/epics2web/monitor" |
| autoOpen | Whether to automatically connect to socket immediately instead of waiting for open function to be called | true |
| autoReconnect | If socket is closed, will automatically reconnect after reconnectWaitMillis | true | 
| autoLivenessPingAndTimeout | Will ping the server every pingIntervalMillis and if no response in livenessTimeoutMillis then will close the socket as invalid | true | 
| autoDisplayClasses | As connect state changes will hide and show elements with corresponding state classes: ws-disconnected, ws-connecting, ws-connected | true |
| pingIntervalMillis | Milliseconds to wait between pings | 8000 |
| livenessTimoutMillis | Max milliseconds allowed for server to respond to a ping (via any message) | 2000 | 
| reconnectWaitMillis | Milliseconds to wait after socket closed before attempting reconnect | 10000 |

## Example

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
   1. Download epics2web.war and drop it into the webapps directory
   1. Start Tomcat and navigate your web browser to localhost:8080/epics2web

*Note:* epics2web also works and was tested with GlassFish, and presumably works with WildFly or any other Java web application server that supports web sockets.

## Configuration

This application uses the [Channel Access for Java](http://epics-jca.sourceforge.net/caj/) library.   It requires a working EPICS channel access environment with the environment variable *EPICS_CA_ADDR_LIST* set.

## See Also
- [PV Monitor Runchart](https://github.com/JeffersonLab/runchart)
- [Web Extensible Display Manager (wedm)](https://github.com/JeffersonLab/wedm)

