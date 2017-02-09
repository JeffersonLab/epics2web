<%@page contentType="text/html" pageEncoding="UTF-8"%>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
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
        <h3>Clients (sessions)</h3>
        <table>
            <thead>
                <tr>
                    <th>ID</th>
                    <th>Remote Address</th>
                    <th>User Agent</th>
                    <th>PVs</th>
                </tr>
            </thead>
            <tbody>
                <c:forEach items="${clientMap}" var="client">
                    <tr>
                        <td><c:out value="${client.key.id}"/></td>
                        <td><c:out value="${client.key.userProperties.ip eq null ? client.key.remoteAddr : client.key.userProperties.ip}"/></td>
                        <td><c:out value="${client.key.userProperties.agent}"/></td>
                        <td>(${client.value == null ? '0' : client.value.size()}) <c:out value="${client.value}"/></td>
                    </tr>
                </c:forEach>
            </tbody>
        </table>
        <h3>Monitors (PVs)</h3>
        <table>
            <thead>
                <tr>
                    <th>PV</th>
                    <th>Clients</th>
                </tr>
            </thead>
            <tbody>
                <c:forEach items="${monitorMap}" var="monitor">
                    <tr>
                        <td><c:out value="${monitor.key}"/></td>
                        <td><c:out value="${monitor.value}"/></td>
                    </tr>
                </c:forEach>
            </tbody>
        </table>
    </body>
</html>
