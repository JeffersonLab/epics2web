<%@page contentType="text/html" pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html>
    <head>
        <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
        <title>epics2web - Test caget</title>
        <link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/resources/css/site.css?v=${initParam.releaseNumber}"/>        
        <link rel="stylesheet" type="text/css" href="${pageContext.request.contextPath}/resources/css/overview.css?v=${initParam.releaseNumber}"/>        
    </head>
    <body>
        <h1>epics2web</h1>
        <h2>Test caget</h2>      
        <table>
            <thead>
                <tr>
                    <th>CA Get</th>
                </tr>
            </thead>
            <tbody>
                <tr>
                    <td>
                        <form action="caget" method="get">
                            <label for="pvs">PV NAMES</label>
                            <input id="pvs-input" type="text" name="pv-list" placeholder="Space Separated"/>
                            <button id="caget-button" type="submit">Get</button>
                        </form>
                        <form id="caget-form" action="caget" method="get" style="display: none;">
                        </form>
                    </td>
                </tr>
            </tbody>
        </table>
        <script type="text/javascript" src="${pageContext.request.contextPath}/resources/js/jquery-1.10.2.min.js"></script>
        <script type="text/javascript" src="${pageContext.request.contextPath}/resources/js/test-caget.js?v=${initParam.releaseNumber}"></script>
    </body>
</html>
