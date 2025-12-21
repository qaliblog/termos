package com.iiordanov.pubkeygenerator;

import com.trilead.ssh2.crypto.PEMDecoder;

import java.io.IOException;

/**
 * Utility class for handling SSH public/private key operations.
 */
public class PubkeyUtils {
    
    /**
     * Wrapper class that provides trilead KeyPair-like interface
     * with .private and .public properties for Kotlin compatibility.
     * The actual key objects are stored as Object to work with trilead's internal types.
     */
    public static class KeyPair {
        public final Object privateKey;
        public final Object publicKey;
        
        public KeyPair(Object privateKey, Object publicKey) {
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }
        
        // Properties for Kotlin compatibility (kp.private, kp.public)
        public Object getPrivate() { return privateKey; }
        public Object getPublic() { return publicKey; }
    }
    
    /**
     * Decrypts and recovers a KeyPair from an SSH private key string.
     * 
     * @param sshPrivKey The SSH private key in PEM format
     * @param passphrase The passphrase to decrypt the key (can be empty string if not encrypted)
     * @return The KeyPair if successful, null if decryption failed
     */
    public static KeyPair decryptAndRecoverKeyPair(String sshPrivKey, String passphrase) {
        if (sshPrivKey == null || sshPrivKey.trim().isEmpty()) {
            return null;
        }
        
        try {
            char[] keyChars = sshPrivKey.toCharArray();
            String passphraseStr = (passphrase != null && !passphrase.isEmpty()) 
                ? passphrase 
                : null;
            
            // PEMDecoder.decode returns Object[] with [PrivateKey, PublicKey]
            // The method signature is: decode(char[] key, String passphrase)
            Object result = PEMDecoder.decode(keyChars, passphraseStr);
            
            if (result instanceof Object[]) {
                Object[] keyPair = (Object[]) result;
                if (keyPair.length >= 2) {
                    return new KeyPair(keyPair[0], keyPair[1]);
                }
            }
            
            return null;
        } catch (IOException e) {
            // Key decryption failed - likely wrong passphrase
            return null;
        } catch (Exception e) {
            // Any other error (unsupported key format, etc.)
            return null;
        }
    }
    
    /**
     * Checks if an SSH private key is encrypted.
     * 
     * @param sshPrivKey The SSH private key in PEM format
     * @return true if the key is encrypted, false otherwise
     */
    public static boolean isEncrypted(String sshPrivKey) {
        if (sshPrivKey == null || sshPrivKey.trim().isEmpty()) {
            return false;
        }
        
        // Check for common encryption indicators in PEM format
        // Encrypted keys typically have headers like "ENCRYPTED" or "Proc-Type: 4,ENCRYPTED"
        String keyUpper = sshPrivKey.toUpperCase();
        return keyUpper.contains("ENCRYPTED") || 
               keyUpper.contains("PROC-TYPE: 4,ENCRYPTED") ||
               keyUpper.contains("DEK-INFO:");
    }
}
