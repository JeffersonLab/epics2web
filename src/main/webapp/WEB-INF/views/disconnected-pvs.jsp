<%@page contentType="text/html" pageEncoding="UTF-8" session="false"%>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<%@taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt"%>
<!DOCTYPE html>
<html>
    <head>
        <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
        <title>epics2web - Disconnected PVs</title>
        <link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/resources/css/site.css?v=${initParam.releaseNumber}"/>
        <c:choose>
            <c:when test="${initParam.productionRelease eq 'true'}">
                <link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/resources/css/disconnected-pvs.min.css?v=${initParam.releaseNumber}"/>
            </c:when>
            <c:otherwise>
                <link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/resources/css/disconnected-pvs.css?v=${initParam.releaseNumber}"/>
            </c:otherwise>
        </c:choose>
    </head>
    <body>
        <h1>epics2web</h1>
        <h2>Disconnected PVs</h2>
        <div id="report" data-myquery-url="<c:out value="${myqueryUrl}"/>" data-myquery-deployment="<c:out value="${myqueryDeployment}"/>">
            <p class="explanation">
                PVs monitored by this server that haven't been connected for more than
                <fmt:formatNumber value="${graceSeconds}"/> seconds, and frozen PVs, as
                <a href="healthcheck">/healthcheck</a> lists them, with the clients that monitor them.
                The IOC comes from the MYA archiver, so it's missing for PVs that aren't archived.
                <em>Via</em> is the last server the PV was reached through, usually a CA gateway.
                Clients name themselves; WEDM screens are shown by their EDL file.
                Generated <c:out value="${generated}"/>.
            </p>
            <h3>Summary</h3>
            <table>
                <thead>
                    <tr>
                        <th>Disconnected</th>
                        <th>Never Connected</th>
                        <th>Frozen</th>
                        <th>Clients Affected</th>
                    </tr>
                </thead>
                <tbody>
                    <tr>
                        <td id="disconnected-count"><fmt:formatNumber value="${report.disconnectedCount}"/></td>
                        <td id="never-connected-count"><fmt:formatNumber value="${report.neverConnectedCount}"/></td>
                        <td id="frozen-count"><fmt:formatNumber value="${report.frozenCount}"/></td>
                        <td id="client-count"><fmt:formatNumber value="${report.clientGroups.size()}"/></td>
                    </tr>
                </tbody>
            </table>
            <c:choose>
                <c:when test="${empty report.rows}">
                    <p id="no-pvs">No disconnected PVs.</p>
                </c:when>
                <c:otherwise>
                    <h3>By IOC</h3>
                    <div id="by-ioc">
                        <c:choose>
                            <c:when test="${empty myqueryUrl}">
                                <p>IOC lookup is off; set MYQUERY_URL to turn it on.</p>
                            </c:when>
                            <c:otherwise>
                                <p class="pending">Looking up IOCs&hellip;</p>
                                <noscript><p>IOC lookup needs JavaScript.</p></noscript>
                            </c:otherwise>
                        </c:choose>
                    </div>
                    <h3>By Client</h3>
                    <table id="by-client">
                        <thead>
                            <tr>
                                <th>Client</th>
                                <th>Sessions</th>
                                <th>Remote Addresses</th>
                                <th>PVs</th>
                            </tr>
                        </thead>
                        <tbody>
                            <c:forEach items="${report.clientGroups}" var="group">
                                <tr>
                                    <td class="client"><c:set var="label" value="${group.label}"/><%@include file="/WEB-INF/views/client-label.jspf"%></td>
                                    <td><fmt:formatNumber value="${group.sessions}"/></td>
                                    <td class="list"><c:forEach items="${group.ips}" var="ip" varStatus="status"><c:out value="${ip}"/><c:if test="${not status.last}">, </c:if></c:forEach></td>
                                    <td class="list">(<fmt:formatNumber value="${group.pvs.size()}"/>) <c:forEach items="${group.pvs}" var="pv" varStatus="status"><c:out value="${pv}"/><c:if test="${not status.last}">, </c:if></c:forEach></td>
                                </tr>
                            </c:forEach>
                        </tbody>
                    </table>
                    <h3>PVs</h3>
                    <table id="pvs">
                        <thead>
                            <tr>
                                <th>PV</th>
                                <th>Status</th>
                                <th>Down For</th>
                                <th>Down Since</th>
                                <th>IOC</th>
                                <th>Via</th>
                                <th>Clients</th>
                            </tr>
                        </thead>
                        <tbody>
                            <c:forEach items="${report.rows}" var="row">
                                <tr data-pv="<c:out value="${row.name}"/>">
                                    <td class="pv"><c:out value="${row.name}"/></td>
                                    <td class="status"><c:out value="${row.status}"/><c:if test="${row.frozenReason ne null}"><div class="frozen-reason"><c:out value="${row.frozenReason}"/></div></c:if></td>
                                    <td class="down-for"><c:out value="${row.downFor}"/></td>
                                    <td class="down-since"><c:out value="${row.downSince}"/></td>
                                    <td class="ioc"><c:if test="${empty myqueryUrl}">&mdash;</c:if></td>
                                    <td class="via"><c:out value="${row.via}"/></td>
                                    <td class="clients">
                                        <ul>
                                            <c:forEach items="${row.clients}" var="client">
                                                <li><c:set var="label" value="${client.label}"/><%@include file="/WEB-INF/views/client-label.jspf"%><c:if test="${client.sessions > 1}"> (&times;<fmt:formatNumber value="${client.sessions}"/>)</c:if></li>
                                            </c:forEach>
                                        </ul>
                                    </td>
                                </tr>
                            </c:forEach>
                        </tbody>
                    </table>
                </c:otherwise>
            </c:choose>
        </div>
        <c:choose>
            <c:when test="${initParam.productionRelease eq 'true'}">
                <script type="text/javascript" src="${pageContext.request.contextPath}/resources/js/disconnected-pvs.min.js?v=${initParam.releaseNumber}"></script>
            </c:when>
            <c:otherwise>
                <script type="text/javascript" src="${pageContext.request.contextPath}/resources/js/disconnected-pvs.js?v=${initParam.releaseNumber}"></script>
            </c:otherwise>
        </c:choose>
    </body>
</html>
