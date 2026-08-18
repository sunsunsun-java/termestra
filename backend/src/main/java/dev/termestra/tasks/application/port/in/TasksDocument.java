package dev.termestra.tasks.application.port.in;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record TasksDocument(String content, String revision) {
    public static final long MAX_TRANSPORT_CONTENT_BYTES = 900L * 1024L;
    public TasksDocument {
        content = Objects.requireNonNull(content, "content must not be null");
        revision = Objects.requireNonNull(revision, "revision must not be null");
    }

    public static TasksDocument from(String content) {
        requireTransportSafe(content);
        return new TasksDocument(content, revisionOf(content));
    }

    private static void requireTransportSafe(String content) {
        long encoded=0;
        for(int index=0;index<content.length();index++){
            char value=content.charAt(index);
            if(value=='"'||value=='\\'||value=='\b'||value=='\f'||value=='\n'||value=='\r'||value=='\t')encoded+=2;
            else if(value<=0x1f)encoded+=6;
            else if(Character.isHighSurrogate(value)&&index+1<content.length()&&Character.isLowSurrogate(content.charAt(index+1))){encoded+=4;index++;}
            else if(Character.isSurrogate(value))encoded+=6;
            else if(value<=0x7f)encoded+=1;
            else if(value<=0x7ff)encoded+=2;
            else encoded+=3;
            if(encoded>MAX_TRANSPORT_CONTENT_BYTES)throw new TasksDocumentTooLarge(MAX_TRANSPORT_CONTENT_BYTES);
        }
    }

    public static String revisionOf(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(content).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
