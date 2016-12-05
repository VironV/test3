package models;

import java.util.*;
import javax.persistence.*;

import com.avaje.ebean.Model;
import play.data.format.*;
import play.data.validation.*;
import java.util.Calendar;
import java.text.DateFormat;
import java.time.LocalDateTime;
import java.time.Duration;

@Entity
public class MapInfo extends Model {

    public MapInfo(String name,boolean is_uploaded,Date upload_date, Date sync_date,boolean sync_success,long downloads_count) {
        this.name=name;
        this.is_uploaded=is_uploaded;
        this.upload_date=upload_date;
        this.sync_date=sync_date;
        this.sync_success=sync_success;
        this.downloads_count=downloads_count;
    }

    @Id
    public Long id;

    @Constraints.Required
    @Column(unique=true)
    public String name;

    public boolean is_uploaded;

    public Date upload_date;

    public Date sync_date;

    public boolean sync_success;

    public long downloads_count;

    public static Finder<Long, MapInfo> find = new Finder<Long,MapInfo>(MapInfo.class);

    public static List<MapInfo> all() {
        return new ArrayList<MapInfo>();
    }

    /*
    public static MapInfo create(String name,Date date) {

        MapInfo map= new MapInfo();
        map.name=name;
        map.downloaded=false;
        map.last_download=date;
        map.save();
        return map;
    }
    */
}