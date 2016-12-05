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
public class MapInfoController extends Controller {

    @Inject WSClient ws;
    @Inject FormFactory formFactory;

    static Form<FormData> myform;
    public String version;
    final static String meta_serv_url = "http://metaserver-resources.mapswithme.com/server_data/active_servers";
    final static String contries_file_url = "https://raw.githubusercontent.com/mapsme/omim/master/data/countries.txt";
    final static String files_location = "C:\\Users\\Viron_2\\IdeaProjects\\test3\\public\\maps\\";
    final static String files_format = ".mwm";

    @Inject public MapInfoController(ActorSystem system) {
        version="161105";

        /*
        system.scheduler().schedule(
                Duration.create(9000, TimeUnit.MILLISECONDS), //Initial delay 0 milliseconds
                Duration.create(1, TimeUnit.HOURS),     //Frequency 5 minutes
                new Runnable() {
                    public void run() {
                        UpdateRequestsController.globalSync();
                    }
                },
                system.dispatcher()
        );
        */
    }

    ///
    /// direct routesactions
    ///

    public Result maps(){
        myform = formFactory.form(FormData.class);
        return ok(mapfile.render(getAllMaps(),myform.bindFromRequest()));
    }

    public Result newMap() {
        Form<FormData> filledForm=myform.bindFromRequest();
        if (filledForm.hasErrors()) {
            return redirect(routes.MapInfoController.maps());
        } else {
            addNewMapInfo(filledForm.get().getName());
            return redirect(routes.MapInfoController.maps());
        }
    }

    public Result deleteMap(Long id) {
        MapInfo map = getMapById(id);
        if (map==null) {
            Logger.debug("There is no map with this id");
            return redirect(routes.MapInfoController.maps());
        }
        File file = new File(files_location + map.name + files_format);

        try {
            file.delete();
        } catch(Throwable e) {
            Logger.debug("Exception on deleting file " + map.name + ": " + e);
            return redirect(routes.MapInfoController.maps());
        }
        Logger.debug("File " + map.name + " deleted");
        map.delete();
        return redirect(routes.MapInfoController.maps());
    }

    public Result downloadMap(Long id) {
        MapInfo map=getMapById(id);
        if (map!=null && (map.is_uploaded && file_downloaded(map.name))){
            MapInfo map_upd=new MapInfo(map.name,map.is_uploaded,map.upload_date,map.sync_date,map.sync_success,map.downloads_count+1);
            map_upd.id=map.id;
            map_upd.update();
            map.update();
            return ok(new File(files_location + map_upd.name + files_format));
        } else {
            flash("error", "This map is not uploaded to server yet");
            return redirect(routes.MapInfoController.maps());
        }
    }

    public Result sendByRequest(String inp_ver, String file) {
        String map_name=file.replace(".mwm","");
        if (inp_ver.equals(version) && file_downloaded(map_name)) {
            MapInfo map=getMapWithName(map_name);
            if (map!=null) {
                return downloadMap(map.id);
            }
        }
        return notFound("<h2>Not found map file with this version and name<h2>").as("text/html");
    }

    public Result deleteAll() {
        List<MapInfo> maps = getAllMaps();
        for (MapInfo map: maps) {
            deleteMap(map.id);
        }

        return redirect(routes.MapInfoController.maps());
    }

    public void addNewMapInfo(String name) {
        MapInfo map=new MapInfo(name,false,null,null,false,0);
        try {
            map.save();
            Logger.debug("Created map info: " + map.name);
            //updateMap(map.id);
        } catch (Throwable e) {
            flash("error", "Map with same name already exists");
        }
    }

    //delete all map info before using
    //TODO: create "refresh maps names" function for updating names without deleting statistic
    //now renaming possible only with pure code
    public Result addAllMapInfo() {
        CompletionStage<WSResponse> jsonPromise= ws.url(contries_file_url).get();
        JsonNode info_file = jsonPromise.toCompletableFuture().join().asJson();

        JsonNode mapsNode=info_file.path("g");
        Iterator<JsonNode> elements = mapsNode.elements();
        while(elements.hasNext()) {
            JsonNode country=elements.next();
            String id_name=country.path("id").asText();
            Logger.debug("map content: " + id_name);

            JsonNode regions=country.path("g");
            Iterator<JsonNode> r_elements=regions.elements();
            if (!r_elements.hasNext()) {
                addNewMapInfo(id_name);
                Logger.debug("---added " + id_name);
            }

            while (r_elements.hasNext()){
                JsonNode region=r_elements.next();
                String download_name=region.path("id").asText();

                addNewMapInfo(download_name);

                Logger.debug("---added " + download_name);
            }
        }

        return redirect(routes.MapInfoController.maps());
    }

    public Result addRussiaMapInfo() {
        CompletionStage<WSResponse> jsonPromise= ws.url(contries_file_url).get();
        JsonNode info_file = jsonPromise.toCompletableFuture().join().asJson();

        JsonNode mapsNode=info_file.path("g");
        Iterator<JsonNode> elements = mapsNode.elements();
        while(elements.hasNext()) {
            JsonNode country=elements.next();
            String id_name=country.path("id").asText();

            if (id_name.contains("Russian")) {
                JsonNode regions = country.path("g");
                Iterator<JsonNode> r_elements = regions.elements();
                while (r_elements.hasNext()) {
                    JsonNode region = r_elements.next();
                    String download_name = region.path("id").asText();

                    addNewMapInfo(download_name);

                    Logger.debug("---added " + download_name);
                }
            }
        }

        return redirect(routes.MapInfoController.maps());
    }

    ///
    /// Helpers
    ///

    public boolean file_downloaded(String map_name) {
        File file = new File(files_location + map_name + files_format);
        if (file.exists()) {
            return true;
        } else {
            MapInfo map = MapInfo.find.where().like("name", "%"+map_name+"%").findList().get(0);
            map.is_uploaded=false;
            map.save();
            return false;
        }
    }

    public List<MapInfo> getAllMaps() {
        List<MapInfo> list=new ArrayList<MapInfo>();
        try {
            list=MapInfo.find.all();
        } catch (Throwable e) {
            Logger.debug("Exception at gettin' all maps: " + e);
        }
        return list;
    }

    public MapInfo getMapWithName(String map_name) {
        MapInfo map=null;
        try {
            map = MapInfo.find.where().like("name", "%" + map_name + "%").findList().get(0);
        } catch (Throwable e) {

        }
        return map;
    }

    public MapInfo getMapById(Long id) {
        MapInfo map=null;
        try {
            map=MapInfo.find.byId(id);
        } catch (Throwable e) {

        }
        return map;
    }



    public static class FormData {
        private String name;

        public FormData() {}

        public void setName(String name) {
            this.name = name;
        }

        public String getName() {
            return this.name;
        }
    }

}