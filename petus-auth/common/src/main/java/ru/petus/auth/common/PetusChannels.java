package ru.petus.auth.common;

import java.security.SecureRandom;
import java.util.Base64;

/** Channel names and payload framing shared with the petus-connect client mod. */
public final class PetusChannels {
    public static final String NAMESPACE = "petus";
    public static final String CHALLENGE = "petus:challenge";
    public static final String TICKET = "petus:ticket";
    public static final String RESULT = "petus:result";

    /** Bumped whenever the payload layout changes. */
    public static final int PROTOCOL = 3;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PetusChannels() {
    }

    public static String newNonce() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
