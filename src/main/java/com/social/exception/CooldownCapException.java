package com.social.exception;

public class CooldownCapException extends RuntimeException {
    public CooldownCapException(String message) { super(message); }
}