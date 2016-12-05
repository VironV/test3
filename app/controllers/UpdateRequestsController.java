package controllers;

import models.MapInfo;

import play.mvc.*;
import play.data.*;
import play.Logger;
import play.db.ebean.*;
import play.mvc.*;
import play.libs.ws.*;
import play.libs.Json.*;
import play.api.libs.concurrent.Execution;
import play.data.FormFactory;
import play.libs.concurrent.HttpExecutionContext;

import views.html.*;
import views.html.helper.*;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import java.text.DateFormat;
import java.io.*;
import java.nio.channels.*;
import java.lang.*;
import java.text.*;

import javax.inject.*;

import scala.concurrent.duration.Duration;

import com.fasterxml.jackson.databind.JsonNode;

import akka.stream.Materializer;
import akka.stream.javadsl.*;
import akka.util.ByteString;
import akka.util.ByteString.*;

import akka.actor.*;
import scala.compat.java8.FutureConverters;
import static akka.pattern.Patterns.ask;

@Singleton
public class UpdateRequestsController extends Controller {

    MapInfoController MIC;
    @Inject WSClient ws;
    @Inject Materializer materializer;
    final static String meta_serv_url = "http://metaserver-resources.mapswithme.com/server_data/active_servers";
    final static String contries_file_url = "https://raw.githubusercontent.com/mapsme/omim/master/data/countries.txt";
    final static String files_location = "C:\\Users\\Viron_2\\IdeaProjects\\test3\\public\\maps\\";
    final static String files_format = ".mwm";
    final static SimpleDateFormat formatter = new SimpleDateFormat("d MM yyyy HH:mm:ss 'GMT'");
    final static SimpleDateFormat small_formatter = new SimpleDateFormat("yyMMdd");

    @Inject public UpdateRequestsController(ActorSystem system) {
        MIC=new MapInfoController(system);

    }

    public Result updateMap(Long id) {
        MapInfo map = MIC.getMapById(id);
        if (map==null) {
            Logger.debug("There is no map with this id");
            return redirect(routes.MapInfoController.maps());
        }

        List<String> servlist=getServersList();
        String serv_url=getAvailableServer(servlist,map.name);
        if (serv_url==null){
            unSuccessSync(map);
        } else {
            if (outdated(serv_url,map)) {
                uploadMap(serv_url, map);
            } else {
                unNeccessarySync(map);
            }
        }
        return redirect(routes.MapInfoController.maps());
    }

    public Result updateAll() {
        globalSync();
        return redirect(routes.MapInfoController.maps());
    }

    ///
    // Logic
    ///

    public void globalSync() {
        Logger.debug("---Global sync began---");

        CompletionStage<WSResponse> jsonPromise= ws.url(contries_file_url).get();
        JsonNode info_file = jsonPromise.toCompletableFuture().join().asJson();
        String last_version= info_file.path("v").asText();

        Date our_date= parseToDate_small(MIC.version);
        Date their_date= parseToDate_small(last_version);

        // check version
        boolean version_outdated=their_date.after(our_date);
        if (version_outdated) {
            MIC.version=last_version;
            Logger.debug("Our main versin is outdated");
        } else {
            Logger.debug("Main version is up-to-date");
        }
        // Get servers
        List<String> servers = getServersList();

        // Updating
        List<MapInfo> maps = MIC.getAllMaps();
        for (MapInfo map: maps) {
            // get server with 200
            String working_server=getAvailableServer(servers,map.name);

            if (working_server==null) {
                Logger.debug("No availave server for " + map.name);
                unSuccessSync(map);
            } else {
                if (!map.is_uploaded || version_outdated || !MIC.file_downloaded(map.name)) {
                    Logger.debug("Updating " + map.name);
                    uploadMap(working_server,map);
                } else {
                    Logger.debug("No need for update to " + map.name);
                    unNeccessarySync(map);
                }
            }
        }

        Logger.debug("---Global sync ended---");
    }

    private boolean outdated(String url,MapInfo map){
        if (map.upload_date==null) {
            return true;
        }

        boolean is_outdated=false;
        CompletionStage<WSResponse> responsePromise = ws.url(url).get();
        String header = responsePromise.toCompletableFuture().join().getHeader("Last-Modified");

        Date serv_date=parseToDate(header);
        Date last_upload_date= map.upload_date;

        is_outdated=serv_date.after(last_upload_date);
        Logger.debug(map.name + "last update on server: " + header + ". Our file outdated? " + is_outdated);
        return is_outdated;
    }

    public void uploadMap(String url, MapInfo map) {
        String map_name=map.name;
        try {
            File file = new File(files_location + map_name + files_format);

            FileOutputStream outputStream = new FileOutputStream(file);

            // Make the request
            CompletionStage<StreamedResponse> futureResponse =
                    ws.url(url).setMethod("GET").stream();

            CompletionStage<File> downloadedFile = futureResponse.thenCompose(res -> {
                Source<ByteString, ?> responseBody = res.getBody();

                // The sink that writes to the output stream
                Sink<ByteString, CompletionStage<akka.Done>> outputWriter =
                        Sink.<ByteString>foreach(bytes -> outputStream.write(bytes.toArray()));

                // materialize and run the stream
                CompletionStage<File> result = responseBody.runWith(outputWriter, materializer)
                        .whenComplete((value, error) -> {
                            // Close the output stream whether there was an error or not
                            Logger.debug("End writing " + map_name + ". " + "Error: " + error + ", Value:" + value);
                            try {
                                outputStream.close();
                            } catch (IOException e) {};
                            // Update map info in database
                            if (error==null) {
                                successSync(map);
                            } else {
                                unSuccessSync(map);
                            }
                        })
                        .thenApply(v -> file);
                return result;
            });
            Logger.debug("File uploaded: " + downloadedFile.toCompletableFuture().join());
        } catch (IOException ex) {
            Logger.debug("Error in File uploading: ", ex);
            unSuccessSync(map);
            return;
        }
    }

    ///
    /// Getting servers
    ///

    private List<String> getServersList() {
        String url =meta_serv_url;
        CompletionStage<WSResponse> request = ws.url(url).get();
        WSResponse response=request.toCompletableFuture().join();
        List<String> servlist=parseToList(response.getBody());

        for (String serv: servlist) {
            Logger.debug("Working server: " + serv);

        }
        return servlist;
        //checkServers(servlist);
    }


    private String getAvailableServer(List<String> servlist, String map_name) {
        String working_serv=null;
        for (String url: servlist) {
            String serv_url=(url + "android/" + MIC.version + "/" + map_name + ".mwm").replaceAll(" ","%20");
            CompletionStage<WSResponse> responsePromise = ws.url(serv_url).get();
            int response_status = responsePromise.toCompletableFuture().join().getStatus();
            if (response_status==200) {
                working_serv=serv_url;
                break;
            }
        }

        if (working_serv==null)
            Logger.debug("No available server for " + map_name);
        return working_serv;
    }

    ///
    /// States for sync status
    ///

    public void successSync(MapInfo map){
        Date date=new Date();
        MapInfo map_upd=new MapInfo(map.name,true,date,date,true,map.downloads_count);
        map_upd.id=map.id;
        map_upd.update();
    }

    public void unNeccessarySync(MapInfo map) {
        Date date=new Date();
        MapInfo map_upd=new MapInfo(map.name,map.is_uploaded,map.upload_date,date,true,map.downloads_count);
        map_upd.id=map.id;
        map_upd.update();

    }

    public void unSuccessSync(MapInfo map) {
        Date date=new Date();
        MapInfo map_upd=new MapInfo(map.name,map.is_uploaded,map.upload_date,date,false,map.downloads_count);
        map_upd.id=map.id;
        map_upd.update();
    }

    ///
    /// Parsers
    ///

    private List<String> parseToList(String str) {
        str=str.replace("[","").replace("]","");
        str=str.replaceAll("\"","");
        List<String> result = Arrays.asList(str.split(","));
        return result;
    }

    private Date parseToDate(String str) {
        // That's ugly, yeah. But I dunno, it's just not working with any words
        str=str.replace("Mon, ","");
        str=str.replace("Tue, ","");
        str=str.replace("Wed, ","");
        str=str.replace("Thu, ","");
        str=str.replace("Fri, ","");
        str=str.replace("Sat, ","");
        str=str.replace("Sun, ","");

        str=str.replace("Jan ","1 ");
        str=str.replace("Feb ","2 ");
        str=str.replace("Mar ","3 ");
        str=str.replace("Apr ","4 ");
        str=str.replace("May ","5 ");
        str=str.replace("June ","6 ");
        str=str.replace("July ","7 ");
        str=str.replace("Aug ","8 ");
        str=str.replace("Sept ","9 ");
        str=str.replace("Oct ","10 ");
        str=str.replace("Nov ","11 ");
        str=str.replace("Dec ","12 ");

        Date date=new Date();
        try {
            date = formatter.parse(str);
        } catch (ParseException e) {
            Logger.debug("exeption: " + e);
        }
        return date;
    }

    private Date parseToDate_small(String str) {
        Date date=new Date();
        try {
            date=small_formatter.parse(str);
        } catch (ParseException e) {
            Logger.debug("exeption: " + e);
        }
        return date;
    }

}