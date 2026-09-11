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


        // HOME PAGE
        server.createContext(
                "/",
                Main::home
        );


        // HEALTH CHECK
        server.createContext(
                "/health",
                Main::health
        );


        // DG PHOTO API
        server.createContext(
                "/dg/photo",
                Main::dgPhoto
        );


        // JAVA 17 COMPATIBLE
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

        String path =
                ex.getRequestURI()
                        .getPath();


        // Only show page for exact /
        if (!"/".equals(path)) {

            sendText(
                    ex,
                    404,
                    "404 Not Found"
            );

            return;
        }


        if (
                !"GET".equalsIgnoreCase(
                        ex.getRequestMethod()
                )
        ) {

            sendText(
                    ex,
                    405,
                    "Method Not Allowed"
            );

            return;
        }


        String html = """
                <!DOCTYPE html>
                <html>

                <head>

                    <meta charset="UTF-8">

                    <meta
                        name="viewport"
                        content="width=device-width, initial-scale=1.0"
                    >

                    <title>NUSI Membership 2026</title>

                    <style>

                        * {
                            box-sizing: border-box;
                        }

                        body {

                            margin: 0;

                            min-height: 100vh;

                            font-family:
                                Arial,
                                Helvetica,
                                sans-serif;

                            background:
                                #eef4f8;

                            color:
                                #0b3558;
                        }


                        .header {

                            background:
                                #0b3558;

                            color:
                                white;

                            padding:
                                20px;

                            text-align:
                                center;
                        }


                        .header h1 {

                            margin:
                                0;

                            font-size:
                                28px;
                        }


                        .header p {

                            margin:
                                7px 0 0;

                            color:
                                #ff4c5b;

                            font-weight:
                                bold;
                        }


                        .content {

                            min-height:
                                calc(100vh - 110px);

                            display:
                                flex;

                            justify-content:
                                center;

                            align-items:
                                center;

                            padding:
                                25px;
                        }


                        .card {

                            width:
                                100%;

                            max-width:
                                550px;

                            background:
                                white;

                            border-radius:
                                14px;

                            box-shadow:
                                0 7px 25px
                                rgba(0,0,0,.12);

                            padding:
                                35px 25px;

                            text-align:
                                center;
                        }


                        .status {

                            display:
                                inline-block;

                            padding:
                                9px 16px;

                            border-radius:
                                50px;

                            background:
                                #e8f8ee;

                            color:
                                #198754;

                            font-weight:
                                bold;

                            margin:
                                15px 0;
                        }


                        .card h2 {

                            margin:
                                0 0 10px;

                            color:
                                #0b3558;
                        }


                        .card p {

                            line-height:
                                1.6;

                            color:
                                #555;
                        }


                        .small {

                            margin-top:
                                20px;

                            color:
                                #777;

                            font-size:
                                13px;
                        }

                    </style>

                </head>


                <body>


                    <div class="header">

                        <h1>
                            NUSI Membership 2026
                        </h1>

                        <p>
                            Membership Registration Service
                        </p>

                    </div>


                    <div class="content">

                        <div class="card">

                            <h2>
                                NUSI Membership Service
                            </h2>


                            <div class="status">
                                ✓ Service Online
                            </div>


                            <p>

                                NUSI Membership DG Profile
                                service is running successfully.

                            </p>


                            <p class="small">

                                DG Profile Photo Service

                            </p>

                        </div>

                    </div>


                </body>

                </html>
                """;


        byte[] bytes =
                html.getBytes(
                        StandardCharsets.UTF_8
                );


        ex.getResponseHeaders()
                .set(
                        "Content-Type",
                        "text/html; charset=utf-8"
                );


        ex.getResponseHeaders()
                .set(
                        "Cache-Control",
                        "no-store"
                );


        ex.sendResponseHeaders(
                200,
                bytes.length
        );


        try (
                OutputStream out =
                        ex.getResponseBody()
        ) {

            out.write(
                    bytes
            );
        }
    }


    /*************************************************
     * HEALTH CHECK
     *************************************************/

    private static void health(
            HttpExchange ex
    ) throws IOException {

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
        }


        sendJson(
                ex,
                200,
                Map.of(
                        "success",
                        true,
                        "service",
                        "nusi-dg-photo",
                        "time",
                        Instant.now()
                                .toString()
                )
        );
    }


    /*************************************************
     * DG PHOTO
     *************************************************/

    private static void dgPhoto(
            HttpExchange ex
    ) throws IOException {


        if (
                !"POST".equalsIgnoreCase(
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
                            "POST required"
                    )
            );

            return;
        }


        /*********************************************
         * API KEY
         *********************************************/

        if (!API_KEY.isBlank()) {

            String supplied =
                    ex
                            .getRequestHeaders()
                            .getFirst(
                                    "X-API-Key"
                            );


            if (
                    supplied == null ||
                    !constantTimeEquals(
                            API_KEY,
                            supplied
                    )
            ) {

                sendJson(
                        ex,
                        401,
                        Map.of(
                                "success",
                                false,
                                "message",
                                "Unauthorized"
                        )
                );

                return;
            }
        }


        /*********************************************
         * READ REQUEST
         *********************************************/

        JsonNode request;


        try {

            request =
                    JSON.readTree(
                            ex.getRequestBody()
                    );

        } catch (Exception badJson) {

            sendJson(
                    ex,
                    400,
                    Map.of(
                            "success",
                            false,
                            "message",
                            "Invalid JSON"
                    )
            );

            return;
        }


        String indos =
                text(
                        request,
                        "indos"
                )
                        .toUpperCase()
                        .replaceAll(
                                "[^A-Z0-9]",
                                ""
                        );


        String password =
                text(
                        request,
                        "password"
                );


        /*********************************************
         * VALIDATE INDOS
         *********************************************/

        if (
                !INDOS
                        .matcher(indos)
                        .matches()
        ) {

            sendJson(
                    ex,
                    400,
                    Map.of(
                            "success",
                            false,
                            "message",
                            "Invalid INDoS format. Expected 00AA0000."
                    )
            );

            return;
        }


        /*********************************************
         * PASSWORD
         *********************************************/

        if (password.isBlank()) {

            sendJson(
                    ex,
                    400,
                    Map.of(
                            "success",
                            false,
                            "message",
                            "Password is required."
                    )
            );

            return;
        }


        boolean acquired =
                false;


        try {

            acquired =
                    DG_SLOTS.tryAcquire();


            if (!acquired) {

                sendJson(
                        ex,
                        429,
                        Map.of(
                                "success",
                                false,
                                "message",
                                "DG photo service is busy. Please retry in a few seconds."
                        )
                );

                return;
            }


            System.out.println(
                    "DG PHOTO REQUEST | INDoS "
                            + indos
            );


            /*****************************************
             * FETCH DG PHOTO
             *****************************************/

            DgPhotoService.PhotoResult result =
                    DgPhotoService.fetch(
                            indos,
                            password
                    );


            /*****************************************
             * RESPONSE
             *****************************************/

            Map<String, Object> response =
                    new LinkedHashMap<>();


            response.put(
                    "success",
                    true
            );


            response.put(
                    "mimeType",
                    "image/jpeg"
            );


            response.put(
                    "base64",
                    result.base64()
            );


            response.put(
                    "width",
                    result.width()
            );


            response.put(
                    "height",
                    result.height()
            );


            sendJson(
                    ex,
                    200,
                    response
            );


            System.out.println(
                    "DG PHOTO SUCCESS | INDoS "
                            + indos
                            + " | "
                            + result.width()
                            + "x"
                            + result.height()
            );


        } catch (
                IllegalArgumentException e
        ) {

            sendJson(
                    ex,
                    400,
                    Map.of(
                            "success",
                            false,
                            "message",
                            safeMessage(e)
                    )
            );


        } catch (
                Exception e
        ) {

            e.printStackTrace(
                    System.err
            );


            sendJson(
                    ex,
                    502,
                    Map.of(
                            "success",
                            false,
                            "message",
                            safeMessage(e)
                    )
            );


        } finally {

            if (acquired) {

                DG_SLOTS.release();
            }
        }
    }


    /*************************************************
     * JSON TEXT
     *************************************************/

    private static String text(
            JsonNode node,
            String field
    ) {

        JsonNode value =
                node == null
                        ? null
                        : node.get(field);


        return (
                value == null ||
                value.isNull()
        )
                ? ""
                : value
                .asText("")
                .trim();
    }


    /*************************************************
     * SEND JSON
     *************************************************/

    private static void sendJson(
            HttpExchange ex,
            int status,
            Object body
    ) throws IOException {

        byte[] bytes =
                JSON.writeValueAsBytes(
                        body
                );


        ex.getResponseHeaders()
                .set(
                        "Content-Type",
                        "application/json; charset=utf-8"
                );


        ex.getResponseHeaders()
                .set(
                        "Cache-Control",
                        "no-store, no-cache, must-revalidate"
                );


        ex.getResponseHeaders()
                .set(
                        "Pragma",
                        "no-cache"
                );


        ex.getResponseHeaders()
                .set(
                        "X-Content-Type-Options",
                        "nosniff"
                );


        ex.sendResponseHeaders(
                status,
                bytes.length
        );


        try (
                OutputStream out =
                        ex.getResponseBody()
        ) {

            out.write(
                    bytes
            );
        }
    }


    /*************************************************
     * SEND TEXT
     *************************************************/

    private static void sendText(
            HttpExchange ex,
            int status,
            String text
    ) throws IOException {

        byte[] bytes =
                text.getBytes(
                        StandardCharsets.UTF_8
                );


        ex.getResponseHeaders()
                .set(
                        "Content-Type",
                        "text/plain; charset=utf-8"
                );


        ex.sendResponseHeaders(
                status,
                bytes.length
        );


        try (
                OutputStream out =
                        ex.getResponseBody()
        ) {

            out.write(
                    bytes
            );
        }
    }


    /*************************************************
     * SAFE ERROR
     *************************************************/

    private static String safeMessage(
            Throwable e
    ) {

        String message =
                e == null
                        ? "Unknown error"
                        : e.getMessage();


        if (
                message == null ||
                message.isBlank()
        ) {

            return
                    "DG photo retrieval failed.";
        }


        return message.length() > 280
                ? message.substring(
                        0,
                        280
                )
                : message;
    }


    /*************************************************
     * API KEY CHECK
     *************************************************/

    private static boolean constantTimeEquals(
            String a,
            String b
    ) {

        byte[] x =
                a.getBytes(
                        StandardCharsets.UTF_8
                );

        byte[] y =
                b.getBytes(
                        StandardCharsets.UTF_8
                );


        int diff =
                x.length ^
                y.length;


        int length =
                Math.max(
                        x.length,
                        y.length
                );


        for (
                int i = 0;
                i < length;
                i++
        ) {

            byte xb =
                    i < x.length
                            ? x[i]
                            : 0;


            byte yb =
                    i < y.length
                            ? y[i]
                            : 0;


            diff |=
                    xb ^ yb;
        }


        return diff == 0;
    }


    /*************************************************
     * ENV
     *************************************************/

    private static String env(
            String name,
            String fallback
    ) {

        String value =
                System.getenv(
                        name
                );


        return value == null
                ? fallback
                : value.trim();
    }


    /*************************************************
     * INT ENV
     *************************************************/

    private static int intEnv(
            String name,
            int fallback
    ) {

        try {

            return Integer.parseInt(
                    env(
                            name,
                            String.valueOf(
                                    fallback
                            )
                    )
            );

        } catch (
                Exception ignored
        ) {

            return fallback;
        }
    }
}
