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


        server.createContext(
                "/health",
                Main::health
        );


        server.createContext(
                "/dg/photo",
                Main::dgPhoto
        );


        /*
         * JAVA 17 COMPATIBLE
         *
         * Do NOT use:
         *
         * Executors.newVirtualThreadPerTaskExecutor()
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
     * HEALTH
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
         * READ JSON
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


        /*********************************************
         * INDOS
         *********************************************/

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


        /*********************************************
         * PASSWORD
         *********************************************/

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
         * VALIDATE PASSWORD
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


        boolean acquired = false;


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
             * FETCH FROM DG
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


        } catch (Exception e) {

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
     * READ JSON TEXT
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


        ex
                .getResponseHeaders()
                .set(
                        "Content-Type",
                        "application/json; charset=utf-8"
                );


        ex
                .getResponseHeaders()
                .set(
                        "Cache-Control",
                        "no-store, no-cache, must-revalidate"
                );


        ex
                .getResponseHeaders()
                .set(
                        "Pragma",
                        "no-cache"
                );


        ex
                .getResponseHeaders()
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

            out.write(bytes);
        }
    }


    /*************************************************
     * SAFE ERROR MESSAGE
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
     * API KEY COMPARE
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
                System.getenv(name);


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

        } catch (Exception ignored) {

            return fallback;
        }
    }
}
