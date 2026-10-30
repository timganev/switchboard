package io.github.timganev.switchboard.gateway.guardian;

// Without a verdict nothing is sent anywhere: the gateway fails closed.
public class GuardianUnavailableException extends RuntimeException {

    public GuardianUnavailableException(Throwable cause) {
        super("The privacy guardian did not answer", cause);
    }
}
