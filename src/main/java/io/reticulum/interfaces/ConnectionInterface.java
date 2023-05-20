package io.reticulum.interfaces;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.reticulum.identity.Identity;
import io.reticulum.interfaces.auto.AutoInterface;
import io.reticulum.transport.AnnounceQueueEntry;
import io.reticulum.utils.IdentityUtils;

import java.time.Instant;
import java.util.Queue;

import static java.nio.charset.StandardCharsets.UTF_8;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type"
)
@JsonSubTypes({
        @Type(value = AutoInterface.class, name = "AutoInterface")
})
public interface ConnectionInterface {

    boolean OUT();
    boolean IN();
    boolean FWD();
    boolean RPT();

    default String getType() {
        return getClass().getSimpleName();
    }

    boolean isEnabled();

    Identity getIdentity();

    Integer getIfacSize();
    byte[] getIfacKey();

    default Integer getRStatRssi() {
        return null;
    }

    default Integer getRStatSnr() {
        return null;
    }

    void processIncoming(final byte[] data);
    void processOutgoing(final byte[] data);

    void setInterfaceName(String name);
    String getInterfaceName();

    /**
     * Sets the minimum amount of time, in seconds, that should pass between received announces,
     * for any one destination. As an example, setting this value to 3600 means that announces
     * received on this interface will only be re-transmitted and propagated to other interfaces
     * once every hour, no matter how often they are received
     *
     * @return seconds
     */
    Integer getAnnounceRateTarget();

    /**
     * Defines the number of times a destination can violate the announce rate before the target rate is enforced
     *
     * @return number
     */
    Integer getAnnounceRateGrace();

    /**
     * configures an extra amount of time that is added to the normal rate target.
     * As an example, if a penalty of 7200 seconds is defined, once the rate target is enforced,
     * the destination in question will only have its announces propagated every 3 hours,
     * until it lowers its actual announce rate to within the target
     *
     * @return seconds
     */
    Integer getAnnounceRatePenalty();

    InterfaceMode getMode();

    Queue<AnnounceQueueEntry> getAnnounceQueue();

    Double getAnnounceCap();
    void setAnnounceCap(double newAnnounceCap);

    Instant getAnnounceAllowedAt();
    void  setAnnounceAllowedAt(Instant announceAllowedAt);

    Integer getBitrate();

    default void detach() {
        //pass
    }

    default byte[] getHash() {
        return IdentityUtils.fullHash(getInterfaceName().getBytes(UTF_8));
    };

    default byte[] getTunnelId() {
        return null;
    }

    default ConnectionInterface getParentInterface() {
        return null;
    }

    default boolean isLocalSharedInstance() {
        return false;
    }

    default boolean isConnectedToSharedInstance() {
        return false;
    }

    void processAnnounceQueue();

    /**
     * Have to be threadsafe
     *
     * @param tunnelId
     */
    default void setTunnelId(byte[] tunnelId) {}

    default boolean wantsTunnel() {
        return false;
    }

    default void setWantsTunnel(boolean wantsTunnel) {}
}
