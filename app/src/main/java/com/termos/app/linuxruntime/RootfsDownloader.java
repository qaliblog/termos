package com.termos.app.linuxruntime;

import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Downloads rootfs files and dependencies.
 * Ported from aterm's downloader logic.
 */
public class RootfsDownloader {
    
    public interface ProgressCallback {
        void onProgress(long downloaded, long total);
    }
    
    public static class AbiUrls {
        public final String talloc;
        public final String proot;
        public final String alpine;
        public final String ubuntu;
        public final String ubuntu22;
        public final String kali;

        public AbiUrls(String talloc, String proot, String alpine, String ubuntu, String ubuntu22, String kali) {
            this.talloc = talloc;
            this.proot = proot;
            this.alpine = alpine;
            this.ubuntu = ubuntu;
            this.ubuntu22 = ubuntu22;
            this.kali = kali;
        }

        public String getRootfsUrl(int workingMode) {
            // 0 = ALPINE, 2 = UBUNTU
            return workingMode == 2 ? ubuntu : alpine;
        }

        public String getRootfsUrl(String distroType) {
            switch (distroType.toLowerCase()) {
                case "ubuntu22":
                    return ubuntu22;
                case "kali":
                    return kali;
                case "ubuntu":
                    return ubuntu;
                case "alpine":
                default:
                    return alpine;
            }
        }
    }
    
    private static final AbiUrls ABI_X86_64 = new AbiUrls(
        "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/x86_64/libtalloc.so.2",
        "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/x86_64/proot",
        "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/x86_64/alpine-minirootfs-3.21.0-x86_64.tar.gz",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/ubuntu-base-20.04.5-base-amd64.tar.gz",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-amd64.tar.gz",
        "https://kali.download/base-images/kali-2024.4/kali-linux-2024.4-rootfs-amd64.tar.xz"
    );
    
    private static final AbiUrls ABI_ARM64_V8A = new AbiUrls(
        "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/aarch64/libtalloc.so.2",
        "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/aarch64/proot",
        "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/ubuntu-base-20.04.5-base-arm64.tar.gz",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz",
        "https://kali.download/base-images/kali-2024.4/kali-linux-2024.4-rootfs-arm64.tar.xz"
    );
    
    private static final AbiUrls ABI_ARMEABI_V7A = new AbiUrls(
        "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/arm/libtalloc.so.2",
        "https://raw.githubusercontent.com/Xed-Editor/Karbon-PackagesX/main/arm/proot",
        "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/armhf/alpine-minirootfs-3.21.0-armhf.tar.gz",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/ubuntu-base-20.04.5-base-armhf.tar.gz",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-armhf.tar.gz",
        "https://kali.download/base-images/kali-2024.4/kali-linux-2024.4-rootfs-armhf.tar.xz"
    );
    
    /**
     * Get ABI URLs for current device
     */
    public static AbiUrls getAbiUrls() {
        String[] abis = Build.SUPPORTED_ABIS;
        for (String abi : abis) {
            if ("x86_64".equals(abi)) {
                return ABI_X86_64;
            } else if ("arm64-v8a".equals(abi)) {
                return ABI_ARM64_V8A;
            } else if ("armeabi-v7a".equals(abi)) {
                return ABI_ARMEABI_V7A;
            }
        }
        throw new RuntimeException("Unsupported CPU architecture");
    }
    
    /**
     * Download a file with progress callback
     */
    public static void downloadFile(String urlString, File outputFile, ProgressCallback callback) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(30000);
        connection.connect();
        
        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new Exception("Failed to download: HTTP " + connection.getResponseCode());
        }
        
        long totalBytes = connection.getContentLength();
        long downloadedBytes = 0;
        
        outputFile.getParentFile().mkdirs();
        
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(outputFile)) {
            
            byte[] buffer = new byte[8 * 1024];
            int bytesRead;
            
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
                downloadedBytes += bytesRead;
                
                if (callback != null) {
                    callback.onProgress(downloadedBytes, totalBytes);
                }
            }
        }
        
        // Make executable if it's proot
        if (outputFile.getName().equals("proot")) {
            outputFile.setExecutable(true, false);
        }
    }
    
    /**
     * Download rootfs file
     */
    public static void downloadRootfs(String urlString, File outputFile, ProgressCallback callback) throws Exception {
        downloadFile(urlString, outputFile, callback);
    }
}

