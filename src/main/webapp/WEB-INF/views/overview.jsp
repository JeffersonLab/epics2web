<%@page contentType="text/html" pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html>
    <head>
        <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
        <title>epics2web</title>
        <link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/resources/css/site.css?v=${initParam.releaseNumber}"/>        
        <link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/resources/css/overview.css?v=${initParam.releaseNumber}"/>        
    </head>
    <body>
        <h1>epics2web</h1>
        <div id="version">Version: ${initParam.releaseNumber} (${initParam.releaseDate})</div>        
        <table>
            <thead>
                <tr>
                    <th>CA Get</th>
                    <th>CA Monitor</th>
                    <th>CA Monitor Console</th>
                </tr>
            </thead>
            <tbody>
                <tr>
                    <td><a href="test-caget">Test</a></td>
                    <td><a href="test-camonitor">Test</a></td>
                    <td><a href="console">Console</a></td>
                </tr>
            </tbody>
        </table>
    </body>
</html>
