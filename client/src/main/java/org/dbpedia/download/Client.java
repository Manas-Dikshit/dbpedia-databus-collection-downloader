package org.dbpedia.download;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ParseException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;

public class Client {

    private static String defaultTargetPath = "./data/";

    private static String defaultCollection = "https://databus.dbpedia.org/dbpedia/collections/latest-core";

    private static String defaultSparqlEndpoint = "https://databus.dbpedia.org/sparql";

    public enum GraphMode {
        NO_GRAPH,
        DOWNLOAD_URL
    }

    /**
     * Quick and dirty implementation of a download client, downloading the contents of a
     * Databus collection. Code is ugly and I know it!
     * This download process will be replaced by the official databus download client as soon as it
     * takes less time to install than writing this code :)
     * @param args
     */
    public static void main(String[] args) {

        Options options = new Options();
        options.addOption("p", "path", true, "The data path");
        options.addOption("c", "collection", true, "The Databus collection to be downloaded");
        options.addOption("g", "graph-mode", true, "change the mode in which .graph files are created");
        options.addOption("s", "sparql-endpoint", true, "the target sparql endpoint");

        String targetPath = defaultTargetPath;
        String collection = defaultCollection;
        String sparqlEndpoint = defaultSparqlEndpoint;

        GraphMode gmode = GraphMode.NO_GRAPH;
        CommandLineParser cmdParser = new DefaultParser();

        try {
            CommandLine cmd = cmdParser.parse(options, args);

            if (cmd.hasOption("p")) {
                targetPath = cmd.getOptionValue("p");
            }

            if (cmd.hasOption("c")) {
                collection = cmd.getOptionValue("c");
            }

            if (cmd.hasOption("s")) {
                sparqlEndpoint = cmd.getOptionValue("s");
            }

            if (cmd.hasOption("g")) {
                String mode = cmd.getOptionValue("g");

                switch (mode.toLowerCase()) {
                    case "":
                    case "no":
                        gmode = GraphMode.NO_GRAPH;
                        break;
                    case "download-url":
                        gmode = GraphMode.DOWNLOAD_URL;
                        break;
                    default:
                        System.out.println("Unknown graph mode: " + mode + " - defaulting to NO_GRAPH");
                        gmode = GraphMode.NO_GRAPH;
                }
            }

            if (!targetPath.endsWith("/") && !targetPath.endsWith(File.separator)) {
                targetPath += File.separator;
            }

            Path targetDir = Paths.get(targetPath);

            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }

            System.out.println("Loading collection " + collection);

            String query = get("GET", collection, "text/sparql");

            if (query == null || query.isEmpty()) {
                System.err.println("Failed to retrieve SPARQL query from collection URL.");
                return;
            }

            System.out.println("Collections resolved to query:");
            System.out.println(query);

            // Depending on the system, daytime or weather condition, the query is either already URL encoded or still plain text
            System.out.println("CHECKING FOR URLENCODED");
            System.out.println("RESULT: " + isURLEncoded(query));

            if (!isURLEncoded(query)) {
                query = URLEncoder.encode(query, "UTF-8");
            }

            String queryResult = query(sparqlEndpoint, query);

            if (queryResult == null || queryResult.isEmpty()) {
                System.err.println("SPARQL query returned no results.");
                return;
            }

            ArrayList<String> files = new ArrayList<>();

            JSONObject obj = new JSONObject(queryResult);
            JSONArray bindings = obj.getJSONObject("results").getJSONArray("bindings");

            for (int i = 0; i < bindings.length(); i++) {
                JSONObject binding = bindings.getJSONObject(i);
                String key = binding.keys().next();
                JSONObject result = binding.getJSONObject(key);
                files.add(result.getString("value"));
            }

            HttpClient client = HttpClientBuilder.create()
                    .setRedirectStrategy(new LaxRedirectStrategy())
                    .build();

            for (String file : files) {
                System.out.println("Downloading file: " + file);

                String filename = file.substring(file.lastIndexOf('/') + 1);

                String prefix = filename;
                String suffixes = "";

                if (filename.contains(".")) {
                    prefix = filename.substring(0, filename.indexOf('.'));
                    suffixes = filename.substring(filename.indexOf('.'));
                }

                String hash = DigestUtils.md5Hex(file).toUpperCase();
                String uniqname = prefix + "_" + hash.substring(0, 8) + suffixes;

                Path outputPath = Paths.get(targetPath, uniqname);

                HttpGet request = new HttpGet(file);
                HttpResponse response = client.execute(request);

                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode != HttpStatus.SC_OK) {
                    System.err.println("Failed to download " + file + " - HTTP " + statusCode);
                    continue;
                }

                try (InputStream in = response.getEntity().getContent();
                     OutputStream out = new FileOutputStream(outputPath.toFile())) {
                    in.transferTo(out);
                }

                if (gmode != GraphMode.NO_GRAPH) {
                    Path graphPath = Paths.get(targetPath + uniqname + ".graph");
                    Files.write(graphPath, file.getBytes("UTF-8"));
                }

                System.out.println("File saved to " + outputPath);
            }

            System.out.println("Done.");

        } catch (org.apache.commons.cli.ParseException e1) {
            System.err.println("Command-line argument parsing failed: " + e1.getMessage());
        } catch (MalformedURLException e) {
            System.err.println("Invalid file URL: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("IO error occurred: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Unexpected error occurred: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String query(String endpoint, String query) throws ParseException, IOException {
        HttpClient client = HttpClientBuilder.create().build();

        String body = "default-graph-uri=&format=application%2Fsparql-results%2Bjson&query=" + query;
        HttpEntity entity = new ByteArrayEntity(body.getBytes("UTF-8"));

        HttpPost request = new HttpPost(endpoint);
        request.setEntity(entity);
        request.setHeader("Content-type", "application/x-www-form-urlencoded");

        HttpResponse response = client.execute(request);
        HttpEntity responseEntity = response.getEntity();

        return (responseEntity != null) ? EntityUtils.toString(responseEntity) : null;
    }

    private static boolean isURLEncoded(String query) {
        Pattern hasWhites = Pattern.compile("\\s+");
        Matcher matcher = hasWhites.matcher(query);
        return !matcher.find();
    }

    private static String get(String method, String urlString, String accept) throws IOException {
        System.out.println(method + ": " + urlString + " / ACCEPT: " + accept);

        HttpClient client = HttpClientBuilder.create().build();

        if (method.equalsIgnoreCase("GET")) {
            HttpGet request = new HttpGet(urlString);
            request.addHeader("Accept", accept);
            HttpResponse response = client.execute(request);
            HttpEntity responseEntity = response.getEntity();
            return (responseEntity != null) ? EntityUtils.toString(responseEntity) : null;
        }

        return null;
    }
}
