package dev.showroom.testdrive.idempotency;

/**
 * What is remembered for one Idempotency-Key: a fingerprint of the request body and,
 * once finished, the response body to replay.
 */
public record IdempotencyRecord(String fingerprint, boolean completed, String responseJson) {

    public static IdempotencyRecord inProgress(String fingerprint) {
        return new IdempotencyRecord(fingerprint, false, null);
    }

    public static IdempotencyRecord completed(String fingerprint, String responseJson) {
        return new IdempotencyRecord(fingerprint, true, responseJson);
    }
}
