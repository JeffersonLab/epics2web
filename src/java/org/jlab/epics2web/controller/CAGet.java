package org.jlab.epics2web.controller;

import gov.aps.jca.dbr.DBR;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jlab.epics2web.Application;

/**
 * Controller for the Overview page.
 * 
 * @author ryans
 */
@WebServlet(name = "CAGet", urlPatterns = {"/caget"})
public class CAGet extends HttpServlet {    
    
    private static final Logger LOGGER = Logger.getLogger(
            CAGet.class.getName());    
    
    /**
     * Handles the HTTP
     * <code>GET</code> method.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        
        // TODO: We should also offer caget over websocket too and make it async
        
        // TODO: We should probably make this an aysnc servlet and use async CA calls otherwise this isn't going to scale at all.
        
        String errorReason = null;
        List<DBR> dbrList = null;
        String jsonp = null;
        String[] pvs = null;

        try {
            pvs = request.getParameterValues("pv");
            jsonp = request.getParameter("jsonp");

            dbrList = Application.channelManager.get(pvs);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unable to obtain dbr list", e);
            errorReason = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        response.setContentType("application/json");

        PrintWriter pw = response.getWriter();

        JsonObjectBuilder builder = Json.createObjectBuilder();

        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm");

        if (errorReason == null) {
            JsonArrayBuilder arrBld = Json.createArrayBuilder();
            if (dbrList != null && pvs != null && dbrList.size() == pvs.length) {
                int i = 0;
                for (DBR dbr : dbrList) {
                    JsonObjectBuilder incBld = Json.createObjectBuilder();
                    incBld.add("name", pvs[i++]);
                    
                    Application.channelManager.addValueToJSON(incBld, dbr);

                    arrBld.add(incBld.build());
                }
            }
            builder.add("data", arrBld.build());
        } else {
            builder.add("error", errorReason);
        }

        String jsonStr = builder.build().toString();

        if (jsonp != null) {
            jsonStr = jsonp + "(" + jsonStr + ");";
        }

        pw.write(jsonStr);

        pw.flush();

        boolean error = pw.checkError();

        if (error) {
            LOGGER.log(Level.SEVERE, "PrintWriter Error");
        }
    }
}
