<%@page contentType="text/html" pageEncoding="UTF-8"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@taglib prefix="app" uri="http://jlab.org/app/functions"%>
<!DOCTYPE html>
<html>
    <head>
        <meta charset="UTF-8">        
        <title>epics2web - Test camonitor</title>
        <link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/resources/css/site.css?v=${initParam.releaseNumber}"/>
        <c:choose>
            <c:when test="${initParam.productionRelease eq 'true'}">
                <link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/resources/css/epics2web.min.css?v=${initParam.releaseNumber}"/> 
            </c:when>
            <c:otherwise>
                <link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/resources/css/epics2web.css?v=${initParam.releaseNumber}"/> 
            </c:otherwise>
        </c:choose>
        <link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/resources/css/test-camonitor.css?v=${initParam.releaseNumber}"/>
    </head>
    <body>
        <h1>epics2web</h1>
        <h2>Test camonitor</h2>
        <h3>
            <span class="socket-status-img">
                <img class="ws-disconnected" title="Socket Disconnected" width="24px" height="24px" src="${pageContext.request.contextPath}/resources/img/disconnected.svg?v=${initParam.releaseNumber}"/>
                <img class="ws-connecting connecting-spinner" title="Socket Connecting" width="24px" height="24px" src="${pageContext.request.contextPath}/resources/img/connecting.svg?v=${initParam.releaseNumber}"/>
                <img class="ws-connected" title="Socket Connected" width="24px" height="24px" src="${pageContext.request.contextPath}/resources/img/connected.svg?v=${initParam.releaseNumber}"/>   
            </span>
            <span class="socket-status-label">Socket Status:</span>
            <span class="socket-status-text">
                <span class="socket-status ws-disconnected">Disconnected</span>
                <span class="socket-status ws-connecting">Connecting</span>
                <span class="socket-status ws-connected">Connected</span>
            </span>
            <span id="socket-input">
                <input type="text" id="pv-input" placeholder="PV Name"/>
                <button type="button" id="go-button">➜</button>
            </span>
        </h3>
        <table id="pv-table">
            <thead>
                <tr>
                    <th>Name</th>
                    <th>Status</th>
                    <th>Type</th>
                    <th>Value</th>
                    <th>Updated</th>
                    <th></th>
                </tr>
            </thead>
            <tbody>
            </tbody>
        </table>
        <script type="text/javascript">
            var jlab = jlab || {};
            jlab.contextPrefix = '${app:contextPrefix()}';
        </script>
        <script type="text/javascript" src="${pageContext.request.contextPath}/resources/js/jquery-1.10.2.min.js"></script>
        <c:choose>
            <c:when test="${initParam.productionRelease eq 'true'}">
                <script type="text/javascript" src="${pageContext.request.contextPath}/resources/js/epics2web.min.js?v=${initParam.releaseNumber}"></script>
            </c:when>
            <c:otherwise>
                <script type="text/javascript" src="${pageContext.request.contextPath}/resources/js/epics2web.js?v=${initParam.releaseNumber}"></script>
            </c:otherwise>
        </c:choose>
        <script type="text/javascript" src="${pageContext.request.contextPath}/resources/js/test-camonitor.js?v=${initParam.releaseNumber}"></script>
    </body>
</html>
