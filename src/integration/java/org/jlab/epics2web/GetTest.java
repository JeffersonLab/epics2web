package org.jlab.epics2web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
import org.junit.Test;

/** Tests /caget against the test IOC. channel1 and channel2 stay at 0. */
public class GetTest {

  @Test
  public void doTest() throws IOException, InterruptedException {
    JsonArray data = getData("pv=channel1");

    assertEquals(1, data.size());
    assertEquals("channel1", data.getJsonObject(0).getString("name"));
    assertEquals(0.0, data.getJsonObject(0).getJsonNumber("value").doubleValue(), 0.1);
  }

  @Test
  public void severalPvs() throws IOException, InterruptedException {
    JsonArray data = getData("pv=channel2&pv=channel1");

    assertEquals(2, data.size());
    assertEquals("channel2", data.getJsonObject(0).getString("name"));
    assertEquals("channel1", data.getJsonObject(1).getString("name"));
  }

  @Test
  public void missingPvIsAnError() throws IOException, InterruptedException {
    JsonObject json = get("pv=channel1&pv=epics2web:test:missing");

    assertTrue("Expected an error, got " + json, json.containsKey("error"));
    assertFalse(json.containsKey("data"));
  }

  /** Returns the data array, failing if the server reports an error instead. */
  private static JsonArray getData(String query) throws IOException, InterruptedException {
    JsonObject json = get(query);
    assertFalse("Server reported an error: " + json, json.containsKey("error"));
    return json.getJsonArray("data");
  }

  private static JsonObject get(String query) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:8080/epics2web/caget?" + query))
            .build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());

    try (JsonReader reader = Json.createReader(new StringReader(response.body()))) {
      return reader.readObject();
    }
  }
}
