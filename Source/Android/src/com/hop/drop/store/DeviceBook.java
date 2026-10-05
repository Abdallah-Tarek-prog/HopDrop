package com.hop.drop.store;

import android.content.Context;
import android.content.SharedPreferences;
import com.hop.drop.core.Json;
import com.hop.drop.core.Peer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DeviceBook implements Peer.Book {
    private final SharedPreferences prefs;
    public DeviceBook(Context context){prefs=context.getSharedPreferences("devices_v2",Context.MODE_PRIVATE);}
    private List<Peer.Device> read(){List<Peer.Device> result=new ArrayList<>();try{
        Object array=Json.parseObject(prefs.getString("data","{\"items\":[]}")).get("items");
        for(Object item:(List<?>)array){Map<String,Object> m=(Map<String,Object>)item;Peer.Device d=new Peer.Device(Json.str(m,"id"),Json.str(m,"name"),Json.str(m,"platform"),(int)Json.num(m,"port"),null);
            d.pairedAt=Json.num(m,"pairedAt");d.lastSeen=Json.num(m,"lastSeen");d.alias=(String)m.get("alias");for(Object a:(List<?>)m.get("addresses"))d.addresses.add((String)a);result.add(d);}
        }catch(Exception ignored){}return result;}
    private void save(List<Peer.Device> list){List<Object> items=new ArrayList<>();for(Peer.Device d:list)items.add(Json.obj("id",d.id,"name",d.name,"platform",d.platform,"port",d.port,"pairedAt",d.pairedAt,"lastSeen",d.lastSeen,"alias",d.alias,"addresses",d.addresses));
        prefs.edit().putString("data",Json.string(Json.obj("items",items))).apply();}
    public synchronized Peer.Device get(String id){for(Peer.Device d:read())if(d.id.equalsIgnoreCase(id))return d;return null;}
    public synchronized List<Peer.Device> all(){return read();}
    public synchronized void put(Peer.Device d){List<Peer.Device> list=read();list.removeIf(x->x.id.equalsIgnoreCase(d.id));list.add(d);save(list);}
    public synchronized void remove(String id){List<Peer.Device> list=read();list.removeIf(x->x.id.equalsIgnoreCase(id));save(list);}
}
