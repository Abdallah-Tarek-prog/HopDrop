package com.hop.drop;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.BatteryManager;
import com.hop.drop.core.BackupRules;
import com.hop.drop.core.Format;
import com.hop.drop.core.Json;
import com.hop.drop.core.Peer;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent phone backups. The receiver service checks them every 30 seconds while it runs. */
final class Backup {
    private static final int ROUND = 500;
    private static final long EVERY_MS = 10 * 60 * 1000;
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static final AtomicBoolean recheckSoon = new AtomicBoolean();
    private static final Set<String> manualPending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<String> sending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<String> again = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static final class Entry {
        final String id, tree, folder, device, status;
        final boolean on, charging, timed;
        final int kinds, from, to;
        final long lastRun, lastCheck;
        Entry(Map<String,Object> m) {
            id = str(m,"id"); tree = str(m,"tree"); folder = str(m,"folder"); device = str(m,"device"); status = str(m,"status");
            on = bool(m,"on"); charging = bool(m,"charging"); timed = bool(m,"timed");
            kinds = number(m,"kinds",BackupRules.ALL); from = number(m,"from",0); to = number(m,"to",0);
            lastRun = longNumber(m,"lastRun"); lastCheck = longNumber(m,"lastCheck");
        }
    }
    private Backup() { }
    private static String str(Map<String,Object> m,String key){Object v=m.get(key);return v instanceof String?(String)v:null;}
    private static boolean bool(Map<String,Object> m,String key){return Boolean.TRUE.equals(m.get(key));}
    private static int number(Map<String,Object> m,String key,int fallback){Object v=m.get(key);return v instanceof Number?((Number)v).intValue():fallback;}
    private static long longNumber(Map<String,Object> m,String key){Object v=m.get(key);return v instanceof Number?((Number)v).longValue():0;}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("backup_v2",Context.MODE_PRIVATE);}

    /** The first entry keeps the old sent-list file in place. Migration needs no spare storage. */
    private static synchronized void migrate(Context c) {
        SharedPreferences p=prefs(c);
        if(p.getBoolean("migrated",false))return;
        SharedPreferences old=c.getSharedPreferences("backup_v1",Context.MODE_PRIVATE);
        String tree=old.getString("tree",null),device=old.getString("device",null);
        if(tree==null||device==null){p.edit().putBoolean("migrated",true).commit();return;}
        String id="legacy";
        String folder=old.getString("folder_name",null);
        Map<String,Object> entry=BackupRules.legacy(id,old.getBoolean("on",false),tree,folder==null?Language.text(c,R.string.ui_folder):folder,device,
                old.getBoolean("charging",false),old.getString("status",null),old.getLong("last_run",0));
        p.edit().putString("entries",Json.string(Json.obj("entries",java.util.Collections.singletonList(entry)))).putBoolean("migrated",true).commit();
    }
    @SuppressWarnings("unchecked")
    private static synchronized List<Map<String,Object>> maps(Context c) {
        migrate(c);
        try {Object value=Json.parseObject(prefs(c).getString("entries","{\"entries\":[]}")).get("entries");
            if(value instanceof List){List<Map<String,Object>> result=new ArrayList<>();for(Object item:(List<?>)value)result.add(new java.util.LinkedHashMap<>((Map<String,Object>)item));return result;}}
        catch(Exception ignored){}
        return new ArrayList<>();
    }
    private static synchronized void save(Context c,List<Map<String,Object>> list){prefs(c).edit().putString("entries",Json.string(Json.obj("entries",list))).commit();}
    static List<Entry> entries(Context c){List<Entry> result=new ArrayList<>();for(Map<String,Object> m:maps(c))result.add(new Entry(m));return result;}
    static Entry get(Context c,String id){for(Entry e:entries(c))if(e.id.equals(id))return e;return null;}
    private static synchronized void change(HopApp app,String id,String key,Object value){List<Map<String,Object>> list=maps(app);for(Map<String,Object> m:list)if(id.equals(m.get("id"))){m.put(key,value);break;}save(app,list);app.changed();}

    static synchronized String create(HopApp app,Uri tree,String folder,String device) {
        String id=UUID.randomUUID().toString();Map<String,Object> entry=BackupRules.legacy(id,false,tree.toString(),folder,device,false,null,0);
        List<Map<String,Object>> list=maps(app);list.add(entry);save(app,list);app.changed();
        return id;
    }
    static void activate(HopApp app,String id,boolean existing){Entry e=get(app,id);if(e==null)return;
        new Thread(()->{if(!existing&&!snapshot(app,id,Uri.parse(e.tree),e.device,e.kinds)){
            status(app,id,"read_error");return;}setOn(app,id,true);},"HopDrop backup add").start();}
    static void setOn(HopApp app,String id,boolean on){change(app,id,"on",on);if(on)check(app,true);}
    static void setKinds(HopApp app,String id,int kinds){change(app,id,"kinds",kinds);check(app,true);}
    static void setCharging(HopApp app,String id,boolean on){change(app,id,"charging",on);check(app,true);}
    static synchronized void setWindow(HopApp app,String id,boolean timed,int from,int to){List<Map<String,Object>> list=maps(app);for(Map<String,Object> m:list)if(id.equals(m.get("id"))){m.put("timed",timed);m.put("from",from);m.put("to",to);break;}save(app,list);app.changed();check(app,true);}
    static void setDevice(HopApp app,String id,String device){Entry before=get(app,id);if(before==null||device.equals(before.device))return;
        change(app,id,"device",device);Entry e=get(app,id);if(e.on)new Thread(()->{
            if(!sentFile(app,id,device).exists()&&!snapshot(app,id,Uri.parse(e.tree),device,e.kinds)){
                setOn(app,id,false);status(app,id,"read_error");return;}
            check(app,true);},"HopDrop backup device").start();}
    static synchronized void setFolder(HopApp app,String id,Uri tree,String folder,boolean existing){List<Map<String,Object>> list=maps(app);for(Map<String,Object> m:list)if(id.equals(m.get("id"))){m.put("tree",tree.toString());m.put("folder",folder);m.put("status",null);m.put("lastCheck",0);break;}save(app,list);app.changed();
        if("legacy".equals(id)){for(Peer.Device device:app.book().all())sentFile(app,id,device.id).delete();}
        else {File[] files=app.getFilesDir().listFiles((dir,name)->name.startsWith("backup-sent-"+id+"-"));if(files!=null)for(File f:files)f.delete();}
        Entry e=get(app,id);if(e!=null)new Thread(()->{if(!existing)snapshot(app,id,tree,e.device,e.kinds);check(app,true);},"HopDrop backup folder").start();}
    static synchronized void remove(HopApp app,String id){List<Map<String,Object>> list=maps(app);list.removeIf(m->id.equals(m.get("id")));save(app,list);app.changed();}

    static boolean snapshot(HopApp app,String id,Uri tree,String device,int kinds){try(FileOutputStream out=new FileOutputStream(sentFile(app,id,device),true)){
        for(FolderScan.Item f:FolderScan.files(app,tree))if(BackupRules.kind(kinds,f.mime,f.name))out.write((key(f)+"\n").getBytes(StandardCharsets.UTF_8));}
        catch(Exception ignored){return false;}return true;
    }
    static void deviceSeen(HopApp app,String id){for(Entry e:entries(app))if(id.equalsIgnoreCase(e.device)&&e.on){check(app,true);break;}}
    static void waiting(HopApp app,Sends.Job job,String label){status(app,job.backupId,"waiting_for",label);}
    private static void status(HopApp app,String id,String text){if(id!=null)change(app,id,"status",text);}
    private static void status(HopApp app,String id,String code,String arg){status(app,id,code+"|"+arg);}
    static String displayStatus(Context context,String value){
        if(value==null)return null;
        int split=value.indexOf('|');String code=split<0?value:value.substring(0,split),arg=split<0?"":value.substring(split+1);
        try{switch(code){
            case "read_error":return Language.text(context,R.string.backup_read_error);
            case "waiting_for":return Language.text(context,R.string.backup_waiting_for,Language.ltr(context,arg));
            case "waiting_charger":return Language.text(context,R.string.backup_waiting_charger);
            case "waiting_time":return Language.text(context,R.string.backup_waiting_time);
            case "pick_device":return Language.text(context,R.string.backup_pick_device);
            case "pick_folder":return Language.text(context,R.string.backup_pick_folder);
            case "up_to_date":return Language.text(context,R.string.backup_up_to_date);
            case "backing_up":{int second=arg.indexOf('|');return second<0?null:Language.text(context,R.string.backup_backing_up,
                    Language.fileObjects(context,Integer.parseInt(arg.substring(0,second))),Language.ltr(context,arg.substring(second+1)));}
            case "save_error":return Language.text(context,R.string.backup_save_error);
            case "backed_up":return Language.text(context,R.string.backup_backed_up,Language.files(context,Integer.parseInt(arg)));
            default:return null;
        }}catch(NumberFormatException badStatus){return null;}
    }
    private static boolean plugged(Context c){Intent battery=c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));return battery!=null&&battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;}

    /** Regular checks obey every rule; a manual check ignores only the time window. */
    static void check(HopApp app,boolean now){check(app,null,false,now);}
    static void check(HopApp app,String id,boolean manual){check(app,id,manual,manual);}
    private static void check(HopApp app,String id,boolean manual,boolean soon){if(app.peer()==null)return;
        if(!busy.compareAndSet(false,true)){if(manual&&id!=null)manualPending.add(id);else if(soon)recheckSoon.set(true);return;}
        new Thread(()->{try{for(Entry e:entries(app))if(id==null||id.equals(e.id))round(app,e,manual,soon);}
            finally{busy.set(false);for(String pending:manualPending)if(manualPending.remove(pending))check(app,pending,true);
                if(recheckSoon.getAndSet(false))check(app,true);}},"HopDrop backup check").start();}
    private static void round(HopApp app,Entry e,boolean manual,boolean soon){
        if(!e.on||sending.contains(e.id))return;
        long now=System.currentTimeMillis();if(!soon&&now-e.lastCheck<EVERY_MS)return;
        int minute=LocalTime.now().getHour()*60+LocalTime.now().getMinute();
        boolean isPlugged=plugged(app);
        if(!BackupRules.allowed(e.on,e.charging,isPlugged,e.timed&&!manual,minute,e.from,e.to)){
            status(app,e.id,e.charging&&!isPlugged?"waiting_charger":"waiting_time");return;}
        Peer.Device device=app.book().get(e.device);if(device==null){status(app,e.id,"pick_device");return;}
        if(e.tree==null){status(app,e.id,"pick_folder");return;}
        change(app,e.id,"lastCheck",now);
        try{Set<String> sent=sent(app,e.id,e.device);List<String[]> picked=new ArrayList<>();
            for(FolderScan.Item f:FolderScan.files(app,Uri.parse(e.tree))){
                if(!BackupRules.kind(e.kinds,f.mime,f.name)||sent.contains(key(f)))continue;
                if(picked.size()<ROUND)picked.add(new String[]{f.uri.toString(),f.name,f.folder,String.valueOf(f.size),key(f)});
            }
            if(picked.isEmpty()){status(app,e.id,"up_to_date");return;}
            Sends.Job job=new Sends.Job(device.id,Language.text(app,R.string.backup_files_to_back_up,Language.files(app,picked.size())));job.listed.addAll(picked);job.backup=true;job.merge=true;job.backupId=e.id;job.backupFolder=e.folder;
            sending.add(e.id);status(app,e.id,"backing_up",picked.size()+"|"+device.label());
            Sends.submit(device.id,()->Sends.run(app,job,false));
        }catch(Exception ex){status(app,e.id,"read_error");}
    }
    static synchronized void sent(HopApp app,Sends.Job job){try(FileOutputStream out=new FileOutputStream(sentFile(app,job.backupId,job.deviceId),true)){
        for(String[] item:job.listed)out.write((item[4]+"\n").getBytes(StandardCharsets.UTF_8));}catch(Exception error){
            change(app,job.backupId,"on",false);status(app,job.backupId,"save_error");return;}
        List<Map<String,Object>> list=maps(app);for(Map<String,Object> m:list)if(job.backupId.equals(m.get("id"))){m.put("lastRun",System.currentTimeMillis());m.put("status","backed_up|"+job.listed.size());break;}save(app,list);app.changed();again.add(job.backupId);
    }
    static void ended(HopApp app,Sends.Job job){sending.remove(job.backupId);if(again.remove(job.backupId))check(app,true);}
    private static File sentFile(Context c,String id,String device){return new File(c.getFilesDir(),BackupRules.sentFile(id,device));}
    private static synchronized Set<String> sent(Context c,String id,String device){Set<String> keys=new HashSet<>();try{File f=sentFile(c,id,device);if(f.exists())keys.addAll(Files.readAllLines(f.toPath(),StandardCharsets.UTF_8));}catch(Exception ignored){}return keys;}
    private static String key(FolderScan.Item f){return f.documentId+"|"+f.size;}
}
