package com.hop.drop;

import android.content.Context;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import javax.net.ssl.SSLException;

/** Localized explanations for error codes from the transport and protocol. */
public final class ErrorText {
    private ErrorText() { }

    public static String forDevice(Context context, Throwable error, String device) {
        if (error == null) return forDevice(context, (String)null, device);
        if (error instanceof SocketTimeoutException) return Language.text(context, R.string.error_timeout, device);
        if (error instanceof ConnectException || error instanceof UnknownHostException)
            return Language.text(context, R.string.error_network, device);
        if (error instanceof SSLException) return Language.text(context, R.string.error_secure, device);
        if (error instanceof FileNotFoundException) return Language.text(context, R.string.error_open_file);
        if (error instanceof EOFException) return Language.text(context, R.string.error_disconnected_during, device);
        String result = forDevice(context, error.getMessage(), device);
        return error instanceof IOException && result.equals(Language.text(context, R.string.error_generic))
                ? Language.text(context, R.string.error_read_connection) : result;
    }

    public static String forDevice(Context context, String error, String device) {
        String name = device == null || device.trim().isEmpty() ? Language.text(context, R.string.error_device_generic) : device;
        String message = error == null ? "" : error.trim();
        String code = message.split(":", 2)[0].trim();
        switch (code) {
            case "not_paired": return Language.text(context, R.string.error_not_paired, name);
            case "identity_mismatch": return Language.text(context, R.string.error_identity);
            case "bad_token": return Language.text(context, R.string.error_qr_invalid);
            case "token_expired": return Language.text(context, R.string.error_qr_expired);
            case "busy": return Language.text(context, R.string.error_busy, name);
            case "rejected": case "user_declined": return Language.text(context, R.string.error_pair_cancelled);
            case "cancelled": return Language.text(context, R.string.error_transfer_cancelled);
            case "declined": return Language.text(context, R.string.error_declined, name);
            case "disconnected": return Language.text(context, R.string.error_disconnected, name);
            case "no_space": return Language.text(context, R.string.error_peer_space, name);
            case "checksum_mismatch": return Language.text(context, R.string.error_checksum);
            case "io_error": return Language.text(context, R.string.error_file_io);
            case "protocol_error": return Language.text(context, R.string.error_protocol, name);
            case "unsupported_version": return Language.text(context, R.string.error_version);
            case "timeout": return Language.text(context, R.string.error_timeout, name);
            case "unreachable": return Language.text(context, R.string.error_unreachable, name);
            case "direct_failed": return Language.text(context, R.string.error_direct, name);
            case "wifi_off": return Language.text(context, R.string.error_wifi_off, name);
            default: break;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("timed out") || lower.contains("timeout")) return Language.text(context, R.string.error_timeout, name);
        if (lower.contains("network") || lower.contains("address") || lower.contains("connect"))
            return Language.text(context, R.string.error_network, name);
        if (lower.contains("permission") || lower.contains("denied")) return Language.text(context, R.string.error_permission);
        if (lower.contains("space") || lower.contains("storage")) return Language.text(context, R.string.error_local_space);
        if (lower.contains("cancel") || lower.contains("declined")) return Language.text(context, R.string.error_pair_cancelled);
        if (lower.contains("not paired") || lower.contains("no longer paired"))
            return Language.text(context, R.string.error_no_longer_paired, name);
        return Language.text(context, R.string.error_generic);
    }
}
