var jlab = jlab || {};
jlab.epics2web = jlab.epics2web || {};
jlab.epics2web.test = jlab.epics2web.test || {};

jlab.epics2web.test.con = null;
jlab.epics2web.test.pvToWidgetMap = {};
jlab.epics2web.test.MAX_MONITORS = 100;
jlab.epics2web.test.enumLabelMap = {};

$(document).on("click", "#go-button", function () {
    var pv = $.trim($("#pv-input").val());

    if (pv === '') {
        alert('Please provide an EPICS PV name');
    } else {
        jlab.epics2web.addPv(pv);
    }

    return false;
});

jlab.epics2web.addPv = function (pv) {
    if (jlab.epics2web.test.con === null) {
        alert('Not connected');
        return;
    }

    if (jlab.epics2web.test.pvToWidgetMap[pv] !== undefined) {
        alert('Already monitoring pv: ' + pv);
        return;
    }

    var $tbody = $("#pv-table tbody");

    if ($tbody.find("tr").length + 1 > jlab.epics2web.test.MAX_MONITORS) {
        alert('Too many monitors; maximum number is: ' + jlab.epics2web.test.MAX_MONITORS);
        return;
    }

    var $tr = $('<tr><td class="pv-name">' + pv + '</td><td class="pv-status"><img style="width: 16px; height: 16px; margin-right: 4px;" alt="Connecting..." src="/epics2web/resources/img/indicator16x16.gif"></img>Connecting</td><td class="pv-type"></td><td class="pv-value"></td><td class="pv-updated"></td><td><button type="button" class="close-button">X</button></td></tr>');

    $tbody.append($tr);

    jlab.epics2web.test.pvToWidgetMap[pv] = $tr;

    var pvs = [pv];

    jlab.epics2web.test.con.monitorPvs(pvs);

    $("#pv-input").val("");
};

$(document).on("click", ".close-button", function () {
    var $tr = $(this).closest("tr"),
            pv = $tr.find(".pv-name").text();
    jlab.epics2web.test.con.clearPvs([pv]);
    $tr.remove();
    delete jlab.epics2web.test.pvToWidgetMap[pv];
    delete jlab.epics2web.test.enumLabelMap[pv];
});

jlab.triCharMonthNames = ["Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
jlab.dateTimeToJLabString = function (x) {
    var year = x.getFullYear(),
            month = x.getMonth(),
            day = x.getDate(),
            hour = x.getHours(),
            minute = x.getMinutes(),
            second = x.getSeconds();

    return jlab.pad(day, 2) + '-' + jlab.triCharMonthNames[month] + '-' + year + ' ' + jlab.pad(hour, 2) + ':' + jlab.pad(minute, 2) + ':' + jlab.pad(second, 2);
};

jlab.pad = function (n, width, z) {
    z = z || '0';
    n = n + '';
    return n.length >= width ? n : new Array(width - n.length + 1).join(z) + n;
};

$(function () {
    $('#pv-input').on("keyup", function (e) {
        if (e.keyCode === 13)
        {
            $("#go-button").click();
        }
    });

    var options = {};

    jlab.epics2web.test.con = new jlab.epics2web.ClientConnection(options);

    jlab.epics2web.test.con.onopen = function (e) {
    };

    jlab.epics2web.test.con.onupdate = function (e) {
        var $tr = jlab.epics2web.test.pvToWidgetMap[e.detail.pv];
        if (typeof $tr !== 'undefined') {

            var value = e.detail.value,
                    type = $tr.find(".pv-type").text();

            if (type === 'DBR_ENUM') {
                var labels = jlab.epics2web.test.enumLabelMap[e.detail.pv];

                value = value.toFixed(0);

                if (typeof labels !== 'undefined') {
                    value = labels[value];
                }
            } else if ($.isNumeric(value)) {
                var int = (type === 'DBR_INT');
                if (int) {
                    value = value.toFixed(0);
                } else {
                    value = value.toFixed(3);
                }
            }

            $tr.find(".pv-value").text(value);
            $tr.find(".pv-updated").text(jlab.dateTimeToJLabString(e.detail.date));
        } else {
            console.log('Server is updating me on a PV I am unaware of: ' + e.detail.pv);
        }
    };

    jlab.epics2web.test.con.oninfo = function (e) {
        var $tr = jlab.epics2web.test.pvToWidgetMap[e.detail.pv];
        if (typeof $tr !== 'undefined') {
            $tr.find(".pv-status").text(e.detail.connected ? 'Connected' : 'Disconnected');
            $tr.find(".pv-type").text(e.detail.datatype);

            if (typeof e.detail['enum-labels'] !== 'undefined') {
                jlab.epics2web.test.enumLabelMap[e.detail.pv] = e.detail['enum-labels'];
            }

        } else {
            console.log('Server is providng me with metadata on a PV I am unaware of: ' + e.detail.pv);
        }
    };
});
