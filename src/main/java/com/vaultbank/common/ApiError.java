package com.vaultbank.common;
import java.time.Instant;
import java.util.Map;
public record ApiError(int status,String message,Map<String,String> errors,Instant timestamp) {
    public static ApiError of(int status,String message){return new ApiError(status,message,Map.of(),Instant.now());}
}
