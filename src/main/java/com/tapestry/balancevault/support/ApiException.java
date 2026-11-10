package com.tapestry.balancevault.support;

/** An expected, user-facing failure carrying the HTTP status to answer with (400, 403, 404, 409, 422, 429 ...). */
public class ApiException extends RuntimeException {
    private final int status;

    /** Creates an exception that GlobalErrorHandler turns into {success:false, error:message} with this status. */
    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** The HTTP status code for the response. */
    public int status() {
        return status;
    }
}
