package com.hop.drop.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import com.hop.drop.BuildConfig;
import com.hop.drop.core.Peer;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public final class LocalSockets implements Peer.Sockets {
    private final ConnectivityManager manager;
    public LocalSockets(Context c){manager=c.getSystemService(ConnectivityManager.class);}
    private static boolean privateIp(InetAddress a){byte[] b=a.getAddress();if(b.length!=4)return false;int x=b[0]&255,y=b[1]&255;return x==10||x==172&&y>=16&&y<=31||x==192&&y==168||x==169&&y==254;}
    private static boolean subnet(InetAddress target,InetAddress local,int bits){byte[] a=target.getAddress(),b=local.getAddress();if(a.length!=4||b.length!=4)return false;for(int i=0;i<4;i++){int n=Math.min(8,bits-i*8);if(n<=0)break;int mask=255<<(8-n);if(((a[i]^b[i])&mask)!=0)return false;}return true;}
    public Socket connect(String address,int port)throws IOException{
        InetAddress target=InetAddress.getByName(address);if(!address.equals(target.getHostAddress()))throw new IOException("Enter a numeric IPv4 address");
        if(BuildConfig.DEBUG&&target.isLoopbackAddress()){Socket s=new Socket();s.connect(new InetSocketAddress(target,port),5000);return s;}
        if(!privateIp(target))throw new IOException("This device isn't on your local network");
        for(Network n:manager.getAllNetworks()){NetworkCapabilities caps=manager.getNetworkCapabilities(n);LinkProperties props=manager.getLinkProperties(n);
            if(caps==null||props==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;
            for(LinkAddress local:props.getLinkAddresses())if(local.getAddress() instanceof Inet4Address&&privateIp(local.getAddress())&&subnet(target,local.getAddress(),local.getPrefixLength())){
                Socket s=n.getSocketFactory().createSocket();s.connect(new InetSocketAddress(target,port),5000);s.setSoTimeout(30000);return s;}}
        try{Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();while(all.hasMoreElements()){NetworkInterface ni=all.nextElement();if(!ni.isUp()||ni.isLoopback()||ni.getName().matches("(?i).*(rmnet|ccmni|pdp|tun|tap|vpn).*") )continue;
            for(java.net.InterfaceAddress ia:ni.getInterfaceAddresses())if(ia.getAddress() instanceof Inet4Address&&privateIp(ia.getAddress())&&subnet(target,ia.getAddress(),ia.getNetworkPrefixLength())){
                Socket s=new Socket();s.connect(new InetSocketAddress(target,port),5000);s.setSoTimeout(30000);return s;}}}catch(Exception e){if(e instanceof IOException)throw(IOException)e;}
        throw new IOException("This device isn't on your local network");
    }
    public boolean localServerAddress(InetAddress address){if(BuildConfig.DEBUG&&address.isLoopbackAddress())return true;if(!privateIp(address))return false;
        try{Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();while(all.hasMoreElements()){NetworkInterface ni=all.nextElement();if(!ni.isUp()||ni.isLoopback()||ni.getName().matches("(?i).*(rmnet|ccmni|pdp|tun|tap|vpn).*"))continue;
            for(Enumeration<InetAddress> ips=ni.getInetAddresses();ips.hasMoreElements();)if(address.equals(ips.nextElement()))return true;}}catch(Exception ignored){}return false;}
    /** One of this phone's local addresses, with the kind of network it belongs to. */
    public static final class Address { public final String ip,kind; Address(String ip,String kind){this.ip=ip;this.kind=kind;} }
    /** This phone's local IPv4 addresses, labelled "Wi-Fi", "Hotspot", "USB tethering", "Ethernet" or "Local network". */
    public static List<Address> describe(Context context){List<Address> result=new ArrayList<>();List<String> seen=new ArrayList<>();ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        for(Network n:manager.getAllNetworks()){NetworkCapabilities caps=manager.getNetworkCapabilities(n);LinkProperties props=manager.getLinkProperties(n);if(caps==null||props==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;
            String kind=caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"Ethernet":"Local network";
            for(LinkAddress local:props.getLinkAddresses())if(local.getAddress() instanceof Inet4Address&&privateIp(local.getAddress())&&!seen.contains(local.getAddress().getHostAddress())){seen.add(local.getAddress().getHostAddress());result.add(new Address(local.getAddress().getHostAddress(),kind));}}
        try{Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();while(all.hasMoreElements()){NetworkInterface ni=all.nextElement();String name=ni.getName().toLowerCase(java.util.Locale.ROOT);if(!ni.isUp()||ni.isLoopback()||name.matches(".*(rmnet|ccmni|pdp|tun|tap|vpn|dummy).*"))continue;
            String kind=name.matches("(rndis|usb|ncm).*")?"USB tethering":name.matches("(wlan|ap|swlan|softap|wigig).*")?"Hotspot":name.startsWith("eth")?"Ethernet":"Local network";
            for(Enumeration<InetAddress> ips=ni.getInetAddresses();ips.hasMoreElements();){InetAddress a=ips.nextElement();if(a instanceof Inet4Address&&privateIp(a)&&!seen.contains(a.getHostAddress())){seen.add(a.getHostAddress());result.add(new Address(a.getHostAddress(),kind));}}}}catch(Exception ignored){}
        return result;}
    public static List<String> addresses(Context context){List<String> result=new ArrayList<>();ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        for(Network n:manager.getAllNetworks()){NetworkCapabilities caps=manager.getNetworkCapabilities(n);LinkProperties props=manager.getLinkProperties(n);if(caps==null||props==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;
            boolean gateway=false;for(android.net.RouteInfo route:props.getRoutes())if(route.isDefaultRoute()&&route.hasGateway())gateway=true;
            if(!gateway)continue;for(LinkAddress local:props.getLinkAddresses())if(local.getAddress() instanceof Inet4Address&&privateIp(local.getAddress())&&!result.contains(local.getAddress().getHostAddress()))result.add(local.getAddress().getHostAddress());}
        try{Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();while(all.hasMoreElements()){NetworkInterface ni=all.nextElement();if(!ni.isUp()||ni.isLoopback()||ni.getName().matches("(?i).*(rmnet|ccmni|pdp|tun|tap|vpn).*"))continue;
        for(Enumeration<InetAddress> ips=ni.getInetAddresses();ips.hasMoreElements();){InetAddress a=ips.nextElement();if(a instanceof Inet4Address&&privateIp(a)&&!result.contains(a.getHostAddress()))result.add(a.getHostAddress());}}}catch(Exception ignored){}return result;}
}
