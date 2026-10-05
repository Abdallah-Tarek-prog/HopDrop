package com.hop.drop.store;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.provider.Settings;
import android.os.Build;
import com.hop.drop.core.Peer;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import javax.security.auth.x500.X500Principal;

public final class Identity {
    private static final String ALIAS="hopdrop_identity_v2";
    public final PrivateKey key;public final X509Certificate cert;
    private Identity(PrivateKey key,X509Certificate cert){this.key=key;this.cert=cert;}
    public static Identity load()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)){
            Calendar now=Calendar.getInstance(),end=Calendar.getInstance();end.add(Calendar.YEAR,30);
            KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
            KeyGenParameterSpec spec=new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(new X500Principal("CN=HopDrop"))
                .setCertificateSerialNumber(new BigInteger(128,new java.security.SecureRandom()).abs().add(BigInteger.ONE))
                .setCertificateNotBefore(now.getTime()).setCertificateNotAfter(end.getTime()).build();
            generator.initialize(spec);generator.generateKeyPair();
        }
        return new Identity((PrivateKey)store.getKey(ALIAS,null),(X509Certificate)store.getCertificate(ALIAS));
    }
    public static String defaultName(Context c){String n=Settings.Global.getString(c.getContentResolver(),"device_name");if(n==null||n.trim().isEmpty())n=Build.MANUFACTURER+" "+Build.MODEL;return n.length()>40?n.substring(0,40):n;}
}
