package org.jlab.epics2web;

import org.junit.Test;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.Assert.assertEquals;

public class GetTest {
    @Test
    public void doTest() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create("http://localhost:8080/epics2web/caget?pv=channel1")).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println(response.body());

        assertEquals(200, response.statusCode());

        try(JsonReader reader = Json.createReader(new StringReader(response.body()))) {
            JsonObject json = reader.readObject();

            JsonArray data = json.getJsonArray("data");

            JsonObject first = data.getJsonObject(0);

            String name = first.getString("name");
            double value = first.getJsonNumber("value").doubleValue();

            assertEquals("channel1", name);
            assertEquals(0.0, value, 0.1);
        }
    }
}
