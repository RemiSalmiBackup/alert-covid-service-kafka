package com.polytech.alertcovidservicekafka.controllers;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.polytech.alertcovidservicekafka.models.*;
import com.polytech.alertcovidservicekafka.services.JsonBodyHandler;
import com.polytech.alertcovidservicekafka.services.LocationLogic;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;


@RestController
@RequestMapping("/stream/location")
public class LocationController {

    @Autowired
    KafkaProducer kafkaProducer;

    private LocationsStreamSingleton locationsStream = LocationsStreamSingleton.getInstance();

    @GetMapping
    public LinkedList<Location> getLocation() {
        return locationsStream.getLocations();
    }

    @PostMapping
    public String create(@RequestHeader("Authorization") String authorization,@RequestBody Location location) {
        Long id_user = this.getIdFromAuthorization(authorization);
        if (id_user.equals(location.getId_user())){
            kafkaProducer.sendMessage(location, "yxffg513-covid_alert");
            return "Publish";
        } else {
            throw new ResponseStatusException( HttpStatus.UNAUTHORIZED, "Your not authorized to do this action" ) ;
        }
    }

    @PostMapping(value="/positive")
    public List<Location> getContactCase(@RequestHeader("Authorization") String authorization, @RequestBody PositiveCase positiveCase) {
        System.out.println(positiveCase);
        Long id_user = this.getIdFromAuthorization(authorization);
        if (id_user.equals(positiveCase.getId_user())){
            LocationLogic locationLogic = new LocationLogic();
            List<Location> contactLocation = locationLogic.getContactLocation(locationsStream.getLocations(), positiveCase.getId_user(), positiveCase.getDate());
            contactLocation.stream().forEach(location -> {
                String userIdJson = "[" + location.getId_user() + "]";
                try {
                    this.postLocationService_thenCorrect(location.toJson(), authorization);
                    this.postNotificationService_thenCorrect(userIdJson,authorization);
                } catch (IOException e) {
                    e.printStackTrace();
                    throw new ResponseStatusException( HttpStatus.SERVICE_UNAVAILABLE, "some service are unavailable retry later" ) ;
                }
            });
            return contactLocation;
        } else {
            throw new ResponseStatusException( HttpStatus.UNAUTHORIZED, "Your not authorized to do this action" ) ;
        }
    }


    ////////////////////////////// PRIVATE METHOD  ////////////////////////////////////////////////

    private void postLocationService_thenCorrect(String contactLocationJson, String authorization)
            throws IOException {
        CloseableHttpClient client = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost("http://localhost:8091/locations/");

        StringEntity entity = new StringEntity(contactLocationJson);
        httpPost.setEntity(entity);
        httpPost.setHeader("Accept", "application/json");
        httpPost.setHeader("Content-type", "application/json");
        httpPost.setHeader("Authorization", authorization);

        CloseableHttpResponse response = client.execute(httpPost);
        assert(response.getStatusLine().getStatusCode() == 200);
        client.close();
    }

    private void postNotificationService_thenCorrect(String usersIdJson, String authorization)
            throws IOException {
        CloseableHttpClient client = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost("http://localhost:8089/notification/");

        StringEntity entity = new StringEntity(usersIdJson);
        httpPost.setEntity(entity);
        httpPost.setHeader("Accept", "application/json");
        httpPost.setHeader("Content-type", "application/json");
        httpPost.setHeader("Authorization", authorization);

        CloseableHttpResponse response = client.execute(httpPost);
        assert(response.getStatusLine().getStatusCode() == 200);
        client.close();
    }

    private long getIdFromAuthorization(String authorization){
        String token = authorization.split(" ")[1];
        DecodedJWT jwt = JWT.decode(token);
        String email = jwt.getClaim("email").asString();

        var client = HttpClient.newHttpClient();
        String url = "http://146.59.234.45:8081/users/" + email;
        var request = HttpRequest.newBuilder(
                URI.create(url))
                .header("Authorization", authorization)
                .build();

        HttpResponse<Supplier<User>> response = null;
        try {
            response = client.send(request, new JsonBodyHandler<>(User.class));
            return response.body().get().id_user;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            throw new ResponseStatusException( HttpStatus.INTERNAL_SERVER_ERROR, "can't get your id from your token" ) ;
        }

    }
}
