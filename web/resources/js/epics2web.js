/* global WebSocket */

var jlab = jlab || {};
jlab.epics2web = jlab.epics2web || {};

/* BEGIN IE CustomEvent POLYFILL */
(function () {
    if (typeof window.CustomEvent === "function") {
        return false;
    }

    function CustomEvent(event, params) {
        params = params || {bubbles: false, cancelable: false, detail: undefined};
        var evt = document.createEvent('CustomEvent');
        evt.initCustomEvent(event, params.bubbles, params.cancelable, params.detail);
        return evt;
    }

    CustomEvent.prototype = window.Event.prototype;

    window.CustomEvent = CustomEvent;
})();
/* END IE CustomEvent POLYFILL */

jlab.epics2web.ClientConnection = function (options) {
    var protocol = 'ws:';
    if (window.location.protocol === 'https:') {
        protocol = 'wss:';
    }

    var defaultOptions = {
        url: protocol + "//" + window.location.host + "/epics2web/monitor",
        autoOpen: true, /* Will automatically connect to socket immediately instead of waiting for open function to be called */
        autoReconnect: true, /* If socket is closed, will automatically reconnect after reconnectWaitMillis */
        autoLivenessPingAndTimeout: true, /* Will ping the server every pingIntervalMillis and if no response in livenessTimeoutMillis then will close the socket as invalid */
        autoDisplayClasses: true, /* As connect state changes will hide and show elements with corresponding state classes: ws-disconnected, ws-connecting, ws-connected */
        pingIntervalMillis: 3000, /* Time to wait between pings */
        livenessTimoutMillis: 2000, /* Max time allowed for server to respond to a ping (via any message) */
        reconnectWaitMillis: 1000, /* Time to wait after socket closed before attempting reconnect */
        chunkedRequestPvsCount: 400 /* Max number of PVs to transmit in a chunked monitor or clear command; 0 to disable chunking */
    };

    if (!options) {
        options = {};
    }

    for (var key in defaultOptions) {
        if (typeof options[key] !== 'undefined') {
            this[key] = options[key];
        } else {
            this[key] = defaultOptions[key];
        }
    }

    // Private variables
    var socket = null,
            eventElem = document.createElement('div'),
            lastUpdated = null,
            self = this,
            livenessTimer = null,
            reconnecting = false;

    // Private functions
    var doPingWithTimer = function () {
        /*console.log('pingWithTimer');*/
        if (socket !== null && socket.readyState === WebSocket.OPEN) {
            self.ping();

            if (livenessTimer !== null) {
                /*console.log('pingWithTimer triggered while liveness timer running (keepAliveTimeout > keepAliveInterval)?');*/
            } else {
                livenessTimer = setTimeout(function () {
                    /*console.log('server liveness timer triggered');*/

                    /*var elapsedMillis = Math.abs(new Date() - lastUpdated);
                     
                     console.log('Elapsed Millis: ' + elapsedMillis);
                     console.log('Keepalive Timeout Millis: ' + self.livenessTimoutMillis);
                     
                     if(elapsedMillis > self.livenessTimoutMillis) {
                     console.log('Ping/Pong Timeout');*/
                    if (socket.readyState === WebSocket.OPEN) {
                        socket.close();
                    }
                    //}

                    livenessTimer = null;
                }, self.livenessTimoutMillis);
            }
        } else {
            /*console.log('socket is not open: onclose may try to reconnect after a delay if not already reconnecting');*/
        }
    };

    // Event wiring
    eventElem.addEventListener('open', function (event) {
        self.onopen(event);
    });
    eventElem.addEventListener('close', function (event) {
        self.onclose(event);
    });
    eventElem.addEventListener('connecting', function (event) {
        self.onconnecting(event);
    });
    eventElem.addEventListener('closing', function (event) {
        self.onclosing(event);
    });
    eventElem.addEventListener('error', function (event) {
        self.onerror(event);
    });
    eventElem.addEventListener('message', function (event) {
        self.onmessage(event);
    });
    eventElem.addEventListener('info', function (event) {
        self.oninfo(event);
    });
    eventElem.addEventListener('update', function (event) {
        self.onupdate(event);
    });
    eventElem.addEventListener('pong', function (event) {
        self.onpong(event);
    });

    this.addEventListener = eventElem.addEventListener.bind(eventElem);
    this.removeEventListener = eventElem.removeEventListener.bind(eventElem);
    this.dispatchEvent = eventElem.dispatchEvent.bind(eventElem);

    // Public functions
    this.open = function () {
        if (socket === null || socket.readyState === WebSocket.CLOSED) {
            var event = new CustomEvent('connecting');
            eventElem.dispatchEvent(event);

            socket = new WebSocket(this.url);

            socket.onerror = function (event) {
                console.log("server connection error");
                console.log(event);

                var event = new CustomEvent('error');
                eventElem.dispatchEvent(event);
            };

            socket.onclose = function (event) {
                console.log("server connection closed");
                console.log(event.reason);

                var event = new CustomEvent('close');
                eventElem.dispatchEvent(event);

                if (livenessTimer !== null) {
                    clearTimeout(livenessTimer);
                    livenessTimer = null;
                }

                var isClosed = socket === null || socket.readyState === WebSocket.CLOSED;
                if (self.autoReconnect && !reconnecting && isClosed) {
                    /*console.log('attempting to reconnect after delay');*/
                    reconnecting = true;
                    setTimeout(function () {
                        /*console.log('reconnect timer triggered');*/
                        self.open();
                        reconnecting = false;
                    }, self.reconnectWaitMillis);
                } else {
                    /*console.log('socket is not closed (socket is connecting, closing, or reconnecting / delayed connecting)');*/
                }
            };

            socket.onmessage = function (event) {
                /*console.log(event.data);*/

                if (livenessTimer !== null) {
                    clearTimeout(livenessTimer);
                    livenessTimer = null;
                }

                lastUpdated = new Date();
                var json = JSON.parse(event.data);
                json.date = lastUpdated;
                if (json.type === 'update') {
                    var event = new CustomEvent('update', {'detail': json});
                    eventElem.dispatchEvent(event);
                } else if (json.type === 'info') {
                    var event = new CustomEvent('info', {'detail': json});
                    eventElem.dispatchEvent(event);
                } else if (json.type === 'pong') {
                    var event = new CustomEvent('pong');
                    eventElem.dispatchEvent(event);
                }

                var event = new CustomEvent('message');
                eventElem.dispatchEvent(event, {'detail': json});
            };

            socket.onopen = function (event) {
                lastUpdated = new Date();

                var event = new CustomEvent('open');
                eventElem.dispatchEvent(event);
            };
        } else {
            console.log('already connected');
            return 1;
        }
    };

    this.close = function (code, reason) {
        console.log('close');
        if (socket !== null && socket.readyState !== WebSocket.CLOSED) {
            if (typeof code === 'undefined') {
                code = 1000;
            }

            socket.close(code, reason);
        } else {
            console.log('already closed');
        }
    };

    this.monitorPvs = function (pvs) {
        if (self.chunkedRequestPvsCount > 0) {
            var i, j, chunk;
            for (i = 0, j = pvs.length; i < j; i += self.chunkedRequestPvsCount) {
                chunk = pvs.slice(i, i + self.chunkedRequestPvsCount);
                this.monitorPvsChunk(chunk);
            }
        } else {
            this.monitorPvsChunk(pvs);
        }
    };

    this.monitorPvsChunk = function (pvs) {
        var msg = {type: 'monitor', pvs: pvs};
        socket.send(JSON.stringify(msg));
    };

    this.clearPvs = function (pvs) {
        if (self.chunkedRequestPvsCount > 0) {
            var i, j, chunk;
            for (i = 0, j = pvs.length; i < j; i += self.chunkedRequestPvsCount) {
                chunk = pvs.slice(i, i + self.chunkedRequestPvsCount);
                this.clearPvsChunk(chunk);
            }
        } else {
            this.clearPvsChunk(pvs);
        }
    };

    this.clearPvsChunk = function (pvs) {
        var msg = {type: 'clear', pvs: pvs};
        socket.send(JSON.stringify(msg));
    };

    this.ping = function () {
        var msg = {type: 'ping'};
        socket.send(JSON.stringify(msg));
    };

    if (this.autoDisplayClasses === true) {
        eventElem.addEventListener('connecting', function (event) {
            $(".ws-disconnected").hide();
            $(".ws-connected").hide();
            $(".ws-connecting").show();
        });
        eventElem.addEventListener('open', function (event) {
            $(".ws-disconnected").hide();
            $(".ws-connected").show();
            $(".ws-connecting").hide();
        });
        eventElem.addEventListener('close', function (event) {
            $(".ws-disconnected").show();
            $(".ws-connected").hide();
            $(".ws-connecting").hide();
        });
    }

    if (this.autoOpen === true) {
        this.open();
    }

    if (this.autoLivenessPingAndTimeout === true) {
        window.setInterval(doPingWithTimer, this.pingIntervalMillis);
    }
};

jlab.epics2web.ClientConnection.prototype.onopen = function () {};
jlab.epics2web.ClientConnection.prototype.onclose = function () {};
jlab.epics2web.ClientConnection.prototype.onconnecting = function () {};
jlab.epics2web.ClientConnection.prototype.onclosing = function () {};
jlab.epics2web.ClientConnection.prototype.onmessage = function () {};
jlab.epics2web.ClientConnection.prototype.onerror = function () {};
jlab.epics2web.ClientConnection.prototype.onupdate = function () {};
jlab.epics2web.ClientConnection.prototype.oninfo = function () {};
jlab.epics2web.ClientConnection.prototype.onpong = function () {};

jlab.epics2web.isNumericEpicsType = function (datatype) {
    var isNumeric;

    switch (datatype) {
        case 'DBR_DOUBLE':
        case 'DBR_FLOAT':
        case 'DBR_INT':
        case 'DBR_SHORT':
        case 'DBR_BYTE':
            isNumeric = true;
            break;
        default:
            isNumeric = false;
    }

    return isNumeric;
};