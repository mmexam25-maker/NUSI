package com.nusi.dg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

public final class Main {

    private static final ObjectMapper JSON =
            new ObjectMapper();

    private static final Pattern INDOS =
            Pattern.compile(
                    "^[0-9]{2}[A-Z]{2}[0-9]{4}$"
            );

    private static final String API_KEY =
            env("API_KEY", "");

    private static final int MAX_CONCURRENT =
            intEnv(
                    "MAX_CONCURRENT_DG",
                    1
            );

    private static final Semaphore DG_SLOTS =
            new Semaphore(
                    Math.max(
                            1,
                            MAX_CONCURRENT
                    )
            );


    private Main() {
    }


    /*************************************************
     * START SERVER
     *************************************************/

    public static void main(
            String[] args
    ) throws Exception {

        int port =
                intEnv(
                        "PORT",
                        10000
                );


        HttpServer server =
                HttpServer.create(
                        new InetSocketAddress(
                                "0.0.0.0",
                                port
                        ),
                        0
                );


        /*********************************************
         * HOME PAGE
         *********************************************/

        server.createContext(
                "/",
                Main::home
        );


        /*********************************************
         * HEALTH
         *********************************************/

        server.createContext(
                "/health",
                Main::health
        );


        /*********************************************
         * DG PHOTO API
         *********************************************/

        server.createContext(
                "/dg/photo",
                Main::dgPhoto
        );


        /*
         * Java 17 compatible.
         */
        server.setExecutor(
                Executors.newCachedThreadPool()
        );


        server.start();


        System.out.println(
                "NUSI DG Photo Service started on port "
                        + port
        );
    }


    /*************************************************
     * HOME PAGE
     *************************************************/

    private static void home(
            HttpExchange ex
    ) throws IOException {

        /*
         * Do not let /health or /dg/photo
         * accidentally come here.
         */
        String path =
                ex.getRequestURI()
                        .getPath();


        if (!"/".equals(path)) {

            String html =
                    """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="UTF-8">
                        <title>404</title>
                    </head>
                    <body>
                        <h2>404 Not Found</h2>
                    </body>
                    </html>
                    """;

            sendHtml(
                    ex,
                    404,
                    html
            );

            return;
        }


        if (
                !"GET".equalsIgnoreCase(
                        ex.getRequestMethod()
                )
        ) {

            sendJson(
                    ex,
                    405,
                    Map.of(
                            "success",
                            false,
                            "message",
                            "Method not allowed"
                    )
            );

            return;
