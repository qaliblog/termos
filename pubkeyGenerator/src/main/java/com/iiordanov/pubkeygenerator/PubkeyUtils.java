package com.iiordanov.pubkeygenerator;

import com.trilead.ssh2.crypto.PEMDecoder;
import com.trilead.ssh2.crypto.PEMStructure;
import com.trilead.ssh2.signature.KeyPair;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

/**
 * Utility class for handling SSH public/private key operations.
 */
public class PubkeyUtils {
    
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
            PEMStructure pem = PEMDecoder.parsePEM(sshPrivKey.toCharArray());
            if (pem == null) {
                return null;
            }
            
            char[] passphraseChars = (passphrase != null && !passphrase.isEmpty()) 
                ? passphrase.toCharArray() 
                : null;
            
            KeyPair keyPair = PEMDecoder.decode(pem, passphraseChars);
            return keyPair;
        } catch (IOException e) {
            // Key decryption failed - likely wrong passphrase
            return null;
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // Unsupported key format
            return null;
        } catch (Exception e) {
            // Any other error
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
