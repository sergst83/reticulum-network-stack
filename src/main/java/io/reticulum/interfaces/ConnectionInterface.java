package io.reticulum.interfaces;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.reticulum.identity.Identity;
import io.reticulum.interfaces.auto.AutoInterface;
import io.reticulum.interfaces.backbone.BackboneClientInterface;
import io.reticulum.interfaces.backbone.BackboneServerInterface;
import io.reticulum.interfaces.tcp.TCPClientInterface;
import io.reticulum.interfaces.tcp.TCPServerInterface;
import io.reticulum.packet.Packet;
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
        @Type(value = AutoInterface.class, name = "AutoInterface"),
        @Type(value = TCPClientInterface.class, name = "TCPClientInterface"),
        @Type(value = TCPServerInterface.class, name = "TCPServerInterface"),
        @Type(value = BackboneClientInterface.class, name = "BackboneClientInterface"),
        @Type(value = BackboneServerInterface.class, name = "BackboneServerInterface")
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

    /**
     * Quality for RNodeInterface
     *
     * @return RStatQ always returns null
     */
    default Integer getRStatQ() {return null; }

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

    /**
     * Returns true if the interface is currently online and able to send/receive.
     *
     * @return boolean true|false if is connected
     */
    default boolean isOnline() {
        return false;
    }

    /**
     * Returns the remote target hostname/IP for client-type interfaces, or null.
     *
     * @return target host
     */
    default String getTargetHost() {
        return null;
    }

    /**
     * Returns the remote target port for client-type interfaces, or 0.
     *
     * @return target port
     */
    default int getTargetPort() {
        return 0;
    }

    /**
     * Returns the autoconnect endpoint hash set by InterfaceDiscovery, or null.
     *
     * @return autoconnecthash bytes
     */
    default byte[] getAutoconnectHash() {
        return null;
    }

    /**
     * Sets the autoconnect endpoint hash.
     *
     * @param hash Hash to be used as a key in future connections
     */
    default void setAutoconnectHash(byte[] hash) {}

    /**
     * Returns the autoconnect source network_id hex string, or null.
     *
     * @return Source Network ID Hex
     */
    default String getAutoconnectSource() {
        return null;
    }

    /**
     * Sets the autoconnect source network_id.
     *
     * @param source Soruce of Autoconnection Endpoint
     */
    default void setAutoconnectSource(String source) {}

    /**
     * Returns the epoch-second when the auto-connected interface went offline, or null.
     *
     * @return Epoch seconds
     */
    default Long getAutoconnectDown() {
        return null;
    }

    /**
     * Sets the epoch-second when the interface went offline (null = online).
     *
     * @param downSince Time Seconds
     */
    default void setAutoconnectDown(Long downSince) {}

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
     * @param tunnelId byte array of length TUNNEL_ID_LENGTH bytes, or NULL if not a tunneled connection
     */
    default void setTunnelId(byte[] tunnelId) {}

    default boolean wantsTunnel() {
        return false;
    }

    default void setWantsTunnel(boolean wantsTunnel) {}

    /**
     * Start interface
     */
    void launch();

    void sentAnnounce(boolean fromSpawned);
    default void sentAnnounce() {
        sentAnnounce(false);
    }

    void processHeldAnnounces();

    default void receivedAnnounce() {
        receivedAnnounce(false);
    }
    void receivedAnnounce(boolean fromSpawned);

    boolean shouldIngressLimit();

    void holdAnnounce(Packet announcePacket);
}
