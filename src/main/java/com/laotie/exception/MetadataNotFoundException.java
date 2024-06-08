package com.laotie.exception;

public class MetadataNotFoundException extends RuntimeException{

    public MetadataNotFoundException(String message) {
        super("table not found: "+message);
    }
}
