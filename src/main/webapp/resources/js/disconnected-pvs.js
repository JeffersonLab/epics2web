/*
 * Fills in the IOC of each PV on the disconnected PVs report from the MYA archiver, through
 * myquery's channel search, and then groups the PVs by IOC. PVs that MYA doesn't archive have no
 * IOC.
 */
(function () {
    'use strict';

    var CONCURRENT_LOOKUPS = 4;

    /* myquery's channel search takes a SQL LIKE pattern, in which _ matches any character, so a PV
       name can match other PVs too; ask for enough matches to include the PV itself. */
    var MATCH_LIMIT = 50;

    var NOT_ARCHIVED = 'Not archived';
    var NO_IOC = 'Archived without an IOC';
    var LOOKUP_FAILED = 'Lookup failed';

    var report = document.getElementById('report');
    var byIoc = document.getElementById('by-ioc');

    if (report === null || byIoc === null) {
        return; /* No PVs */
    }

    var base = report.getAttribute('data-myquery-url');
    var deployment = report.getAttribute('data-myquery-deployment');

    if (!base) {
        return; /* Lookup is off */
    }

    var rows = Array.prototype.slice.call(document.querySelectorAll('#pvs tbody tr[data-pv]'));

    if (!window.fetch) {
        byIoc.textContent = 'IOC lookup needs a newer browser.';
        return;
    }

    var results = {};
    var next = 0;
    var active = 0;
    var finished = 0;

    function lookup(pv) {
        var url = base + '/channel?q=' + encodeURIComponent(pv) + '&l=' + MATCH_LIMIT +
                '&m=' + encodeURIComponent(deployment);

        return fetch(url, {credentials: 'same-origin'}).then(function (response) {
            if (!response.ok) {
                throw new Error('myquery answered ' + response.status);
            }
            return response.json();
        }).then(function (channels) {
            for (var i = 0; i < channels.length; i++) {
                if (channels[i].name === pv) {
                    return {
                        group: channels[i].ioc ? channels[i].ioc : NO_IOC,
                        ioc: channels[i].ioc,
                        active: channels[i].active !== false
                    };
                }
            }
            return {group: NOT_ARCHIVED};
        });
    }

    function show(cell, result) {
        cell.textContent = '';

        if (result.ioc) {
            cell.appendChild(document.createTextNode(result.ioc));
        } else {
            var span = document.createElement('span');
            span.className = 'unknown';
            span.textContent = result.group;
            if (result.error) {
                span.title = result.error;
            }
            cell.appendChild(span);
        }

        if (result.ioc && !result.active) {
            var note = document.createElement('div');
            note.className = 'note';
            note.textContent = 'no longer archived';
            cell.appendChild(note);
        }
    }

    function showProgress() {
        byIoc.textContent = 'Looking up IOCs\u2026 ' + finished + ' of ' + rows.length;
    }

    function launch(row) {
        var pv = row.getAttribute('data-pv');
        var cell = row.querySelector('td.ioc');

        active++;
        cell.textContent = '\u2026';

        lookup(pv).then(function (result) {
            results[pv] = result;
        }, function (error) {
            results[pv] = {group: LOOKUP_FAILED, error: String(error && error.message ? error.message : error)};
        }).then(function () {
            show(cell, results[pv]);
            active--;
            finished++;

            if (finished === rows.length) {
                renderByIoc();
            } else {
                showProgress();
                pump();
            }
        });
    }

    function pump() {
        while (active < CONCURRENT_LOOKUPS && next < rows.length) {
            launch(rows[next++]);
        }
    }

    function isKnown(group) {
        return group !== NOT_ARCHIVED && group !== NO_IOC && group !== LOOKUP_FAILED;
    }

    function addCell(tr, className) {
        var td = document.createElement('td');
        if (className) {
            td.className = className;
        }
        tr.appendChild(td);
        return td;
    }

    function renderByIoc() {
        var groups = {};
        var names = [];

        rows.forEach(function (row) {
            var pv = row.getAttribute('data-pv');
            var name = results[pv].group;
            var group = groups[name];

            if (!group) {
                group = groups[name] = {name: name, pvs: [], since: null, clients: [], clientTexts: {}};
                names.push(name);
            }

            group.pvs.push(pv);

            var since = row.querySelector('td.down-since').textContent.trim();
            if (since && (group.since === null || since < group.since)) {
                group.since = since; /* yyyy-MM-dd HH:mm:ss sorts as text */
            }

            Array.prototype.forEach.call(row.querySelectorAll('td.clients .client-label'), function (label) {
                var key = label.textContent + '\n' + (label.getAttribute('href') || '');
                if (!group.clientTexts[key]) {
                    group.clientTexts[key] = true;
                    group.clients.push(label);
                }
            });
        });

        names.sort(function (a, b) {
            var ka = isKnown(a), kb = isKnown(b);
            if (ka !== kb) {
                return ka ? -1 : 1;
            }
            var byCount = groups[b].pvs.length - groups[a].pvs.length;
            return byCount !== 0 ? byCount : (a < b ? -1 : (a > b ? 1 : 0));
        });

        var table = document.createElement('table');
        table.id = 'by-ioc-table';
        var headRow = table.createTHead().insertRow();
        ['IOC', 'Down Since (Earliest)', 'PVs', 'Clients'].forEach(function (text) {
            var th = document.createElement('th');
            th.textContent = text;
            headRow.appendChild(th);
        });

        var body = table.createTBody();

        names.forEach(function (name) {
            var group = groups[name];
            var tr = document.createElement('tr');
            tr.setAttribute('data-ioc', name);

            var iocCell = addCell(tr, 'ioc-name');
            if (isKnown(name)) {
                iocCell.textContent = name;
            } else {
                var span = document.createElement('span');
                span.className = 'unknown';
                span.textContent = name;
                iocCell.appendChild(span);
            }

            addCell(tr).textContent = group.since === null ? '' : group.since;
            addCell(tr, 'ioc-pvs').textContent = '(' + group.pvs.length + ') ' + group.pvs.join(', ');

            var clientsCell = addCell(tr, 'ioc-clients');
            group.clients.forEach(function (label, i) {
                if (i > 0) {
                    clientsCell.appendChild(document.createTextNode(', '));
                }
                clientsCell.appendChild(label.cloneNode(true));
            });

            body.appendChild(tr);
        });

        byIoc.textContent = '';
        byIoc.appendChild(table);
    }

    showProgress();
    pump();
}());
