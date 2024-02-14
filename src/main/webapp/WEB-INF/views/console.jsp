<%@page contentType="text/html" pageEncoding="UTF-8"%>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<%@taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt"%> 
<!DOCTYPE html>
<html>
    <head>
        <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
        <title>epics2web - Monitor Console</title>
        <link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/resources/css/site.css?v=${initParam.releaseNumber}"/> 
        <link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/resources/css/console.css?v=${initParam.releaseNumber}"/>
    </head>
    <body>
        <h1>epics2web</h1>
        <h2>EPICS CA Web Monitor Console</h2>
        <h3>Summary</h3>
        <table>
            <thead>
                <tr>
                    <th>Total Clients (Sessions)</th>
                    <th>Total PVs (Duplicates Allowed)</th>
                    <th>Unique PVs (Monitors)</th>
                </tr>
            </thead>
            <tbody>
                <c:set var="totalPvs" value="0"/>
                <c:forEach items="${clientMap}" var="client">
                    <c:set var="totalPvs" value="${totalPvs + client.value.size()}"/>
                </c:forEach>
                <tr>
                    <td><fmt:formatNumber value="${clientMap.size()}"/></td>
                    <td><fmt:formatNumber value="${totalPvs}"/></td>
                    <td><fmt:formatNumber value="${monitorMap.size()}"/></td>
                </tr>                    
            </tbody>
        </table>        
        <h3>Clients (sessions)</h3>
        <table>
            <thead>
                <tr>
                    <th>ID</th>
                    <th>Remote Address</th>
                    <th>User Agent</th>
                    <th>Name</th>
                    <th>PVs</th>
                    <th>Dropped Messages</th>
                </tr>
            </thead>
            <tbody>
                <c:forEach items="${clientMap}" var="client">
                    <tr>
                        <td><c:out value="${client.key.id}"/></td>
                        <td><c:out value="${client.key.ip}"/></td>
                        <td><c:out value="${client.key.agent}"/></td>
                        <td><c:out value="${client.key.name}"/></td>
                        <td>(${client.value == null ? '0' : client.value.size()}) <c:out value="${client.value}"/></td>
                        <td><fmt:formatNumber value="${client.key.droppedMessageCount}"/></td>
                    </tr>
                </c:forEach>
            </tbody>
        </table>
        <h3>Monitors (PVs)</h3>
        <table>
            <thead>
                <tr>
                    <th>PV</th>
                    <th>State</th>
                    <th>Last Update</th>
                    <th>Clients</th>
                </tr>
            </thead>
            <tbody>
                <c:forEach items="${monitorMap}" var="monitor">
                    <tr>
                        <td><c:out value="${monitor.key}"/></td>
                        <td><c:out value="${monitor.value.state}"/></td>
                        <td><fmt:formatDate pattern="MMM dd yyy HH:mm:ss" value="${monitor.value.lastTimestamp}"/> - <c:out value="${monitor.value.lastValue}"/></td>
                        <td><c:out value="${monitor.value.listenerCount}"/></td>
                    </tr>
                </c:forEach>
            </tbody>
        </table>
    </body>
</html>
