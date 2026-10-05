package com.hop.drop;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Short explanations for errors displayed outside the protocol layer. */
public final class ErrorText {
    private ErrorText() {
    }

    public static String forDevice(Throwable error, String device) {
        if (error == null) {
            return forDevice((String) null, device);
        }
        if (error instanceof SocketTimeoutException) {
            return device + " stopped responding.";
        }
        if (error instanceof ConnectException || error instanceof UnknownHostException) {
            return "Can't reach " + device + ". Check that both devices are on the same network.";
        }
        if (error instanceof SSLException) {
            return "Couldn't make a secure connection to " + device + ".";
        }
        if (error instanceof FileNotFoundException) {
            return "HopDrop can't open one of the selected files.";
        }
        if (error instanceof EOFException) {
            return device + " disconnected during the transfer.";
        }
        String translated = forDevice(error.getMessage(), device);
        if (error instanceof IOException && translated.equals("Something went wrong. Please try again.")) {
            return "The transfer stopped because a file or connection could not be read.";
        }
        return translated;
    }

    public static String forDevice(String error, String device) {
        String name = device == null || device.trim().isEmpty() ? "The device" : device;
        String message = error == null ? "" : error.trim();
        String code = message.split(":", 2)[0].trim();
        switch (code) {
            case "not_paired":
                return name + " doesn't know this phone any more. Pair again.";
            case "identity_mismatch":
                return "This isn't the device you paired with. Pair again.";
            case "bad_token":
                return "That QR code can't be used. Show a new one and scan it again.";
            case "token_expired":
                return "That QR code expired. Show a new one and scan it again.";
            case "busy":
                return name + " is busy pairing. Try again shortly.";
            case "rejected":
            case "user_declined":
                return "Pairing was cancelled.";
            case "cancelled":
                return "The transfer was cancelled.";
            case "declined":
                return name + " didn't accept the files.";
            case "disconnected":
                return name + " disconnected and didn't come back. Send again to get the rest.";
            case "no_space":
                return name + " doesn't have enough free space.";
            case "checksum_mismatch":
                return "The file changed during transfer. Try sending it again.";
            case "io_error":
                return "The transfer stopped while reading or saving a file.";
            case "protocol_error":
                return "HopDrop couldn't understand the response from " + name + ".";
            case "unsupported_version":
                return "Update HopDrop on both devices, then try again.";
            case "timeout":
                return name + " stopped responding.";
            default:
                break;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return name + " stopped responding.";
        }
        if (lower.contains("network") || lower.contains("address") || lower.contains("connect")) {
            return "Can't reach " + name + ". Check that both devices are on the same network.";
        }
        if (lower.contains("permission") || lower.contains("denied")) {
            return "HopDrop can't access that file or folder. Check its permission.";
        }
        if (lower.contains("space") || lower.contains("storage")) {
            return "There isn't enough free space to save the file.";
        }
        if (lower.contains("cancel") || lower.contains("declined")) {
            return "Pairing was cancelled.";
        }
        if (lower.contains("not paired") || lower.contains("no longer paired")) {
            return name + " doesn't know this device any more. Pair again.";
        }
        return "Something went wrong. Please try again.";
    }
}
