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
        <form action="caget" method="get">        
            <table>
                <thead>
                    <tr>
                        <th colspan="2">CA Get</th>
                    </tr>
                </thead>
                <tbody>
                    <tr>
                        <td style="width:300px; text-align: right;">
                            <label for="n">Return numerical value instead of string for DBR_ENUM field (caget -n): </label>

                        </td>
                        <td style="text-align: left;">
                            <input id="n" type="checkbox" name="n" value="Y"/>
                        </td>
                    </tr>
                    <tr>
                        <td style="text-align: right;">
                            <label for="pvs">PV names: </label>
                        </td>
                        <td style="text-align: left;">
                            <input style="width: 90%;" id="pvs-input" type="text" name="pv-list" placeholder="Space Separated"/>
                        </td>
                    </tr>
                    <tr>
                        <td colspan="2">
                            <button id="caget-button" type="submit">Get</button>
                        </td>
                    </tr>
                </tbody>
            </table>
        </form>                       
        <script type="text/javascript" src="${pageContext.request.contextPath}/resources/js/jquery-1.10.2.min.js"></script>
        <script type="text/javascript" src="${pageContext.request.contextPath}/resources/js/test-caget.js?v=${initParam.releaseNumber}"></script>
    </body>
</html>
